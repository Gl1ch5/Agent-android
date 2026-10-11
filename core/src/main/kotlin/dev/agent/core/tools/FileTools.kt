package dev.agent.core.tools

import kotlinx.serialization.json.JsonObject

private const val MAX_READ_LINES = 2000
private const val MAX_LIST_ENTRIES = 500
private const val MAX_GREP_MATCHES = 200
private const val MAX_FILE_BYTES = 1_000_000

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description =
        "Read a text file inside the workspace. Returns numbered lines. Use offset and limit for large files."
    override val inputSchema = objectSchema(
        "path" to property("string", "Path relative to the workspace root."),
        "offset" to property("integer", "1-based line to start from. Defaults to 1."),
        "limit" to property("integer", "Maximum number of lines. Defaults to $MAX_READ_LINES."),
        required = listOf("path"),
    )

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val file = workspace.resolve(input.requireString("path"))
        if (!file.isFile) return ToolOutput("Not a file: ${workspace.relative(file)}", isError = true)
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutput("File is larger than $MAX_FILE_BYTES bytes; read it in ranges with offset/limit.", true)
        }
        val lines = file.readLines()
        val offset = (input.optionalInt("offset") ?: 1).coerceAtLeast(1)
        val limit = (input.optionalInt("limit") ?: MAX_READ_LINES).coerceIn(1, MAX_READ_LINES)
        val slice = lines.drop(offset - 1).take(limit)
        val text = slice.withIndex().joinToString("\n") { (i, line) -> "${offset + i}\t$line" }
        val more = if (offset - 1 + slice.size < lines.size) "\n... (${lines.size} lines total)" else ""
        return ToolOutput(text.ifEmpty { "(empty)" } + more)
    }
}

class WriteFileTool : Tool {
    override val name = "write_file"
    override val description =
        "Create or overwrite a file inside the workspace with the given content. Parent directories are created."
    override val inputSchema = objectSchema(
        "path" to property("string", "Path relative to the workspace root."),
        "content" to property("string", "Full file content."),
        required = listOf("path", "content"),
    )
    override val requiresApproval = true

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val file = workspace.resolve(input.requireString("path"))
        file.parentFile?.mkdirs()
        file.writeText(input.requireString("content"))
        return ToolOutput("Wrote ${file.length()} bytes to ${workspace.relative(file)}")
    }
}

class EditFileTool : Tool {
    override val name = "edit_file"
    override val description = "Replace an exact string in a file. old_string must occur exactly once unless " +
        "replace_all is true. Read the file first so old_string matches exactly."
    override val inputSchema = objectSchema(
        "path" to property("string", "Path relative to the workspace root."),
        "old_string" to property("string", "Exact text to replace, including whitespace."),
        "new_string" to property("string", "Replacement text."),
        "replace_all" to property("boolean", "Replace every occurrence. Defaults to false."),
        required = listOf("path", "old_string", "new_string"),
    )
    override val requiresApproval = true

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val file = workspace.resolve(input.requireString("path"))
        if (!file.isFile) return ToolOutput("Not a file: ${workspace.relative(file)}", isError = true)
        val oldString = input.requireString("old_string")
        val newString = input.requireString("new_string")
        if (oldString.isEmpty()) return ToolOutput("old_string must not be empty", isError = true)

        val content = file.readText()
        val occurrences = content.windowed(oldString.length).count { it == oldString }
        if (occurrences == 0) return ToolOutput("old_string not found in ${workspace.relative(file)}", true)
        val replaceAll = input.optionalBoolean("replace_all") ?: false
        if (occurrences > 1 && !replaceAll) {
            return ToolOutput("old_string matches $occurrences times; add more context or set replace_all", true)
        }
        val updated = if (replaceAll) content.replace(oldString, newString)
        else content.replaceFirst(oldString, newString)
        file.writeText(updated)
        return ToolOutput("Edited ${workspace.relative(file)} ($occurrences replacement(s))")
    }
}

class ListDirTool : Tool {
    override val name = "list_dir"
    override val description = "List files and directories at a path inside the workspace. Directories end with '/'."
    override val inputSchema = objectSchema(
        "path" to property("string", "Directory relative to the workspace root. Defaults to the root."),
    )

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val dir = workspace.resolve(input.optionalString("path") ?: ".")
        if (!dir.isDirectory) return ToolOutput("Not a directory: ${workspace.relative(dir)}", true)
        val entries = dir.listFiles().orEmpty()
            .sortedBy { it.name }
            .map { if (it.isDirectory) "${it.name}/" else it.name }
        val shown = entries.take(MAX_LIST_ENTRIES)
        val suffix = if (entries.size > shown.size) "\n... ${entries.size - shown.size} more" else ""
        return ToolOutput(shown.joinToString("\n").ifEmpty { "(empty)" } + suffix)
    }
}

class GrepTool : Tool {
    override val name = "grep"
    override val description = "Search file contents with a regular expression. Returns file:line:text matches."
    override val inputSchema = objectSchema(
        "pattern" to property("string", "Kotlin/Java regular expression."),
        "path" to property("string", "Directory or file to search. Defaults to the root."),
        "ignore_case" to property("boolean", "Case-insensitive match. Defaults to false."),
        required = listOf("pattern"),
    )

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val options = if (input.optionalBoolean("ignore_case") == true) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = runCatching { Regex(input.requireString("pattern"), options) }
            .getOrElse { return ToolOutput("Invalid regex: ${it.message}", true) }
        val start = workspace.resolve(input.optionalString("path") ?: ".")
        val matches = mutableListOf<String>()
        start.walkTopDown()
            .onEnter { !it.name.startsWith(".git") && it.name != "build" && it.name != "node_modules" }
            .filter { it.isFile && it.length() <= MAX_FILE_BYTES }
            .forEach { file ->
                if (matches.size >= MAX_GREP_MATCHES) return@forEach
                runCatching { file.readLines() }.getOrNull()?.forEachIndexed { index, line ->
                    if (matches.size < MAX_GREP_MATCHES && regex.containsMatchIn(line)) {
                        matches += "${workspace.relative(file)}:${index + 1}:${line.trim()}"
                    }
                }
            }
        val text = matches.joinToString("\n").ifEmpty { "No matches" }
        return ToolOutput(if (matches.size >= MAX_GREP_MATCHES) "$text\n... (limit reached)" else text)
    }
}
