package dev.agent.core.agent

import dev.agent.core.tools.Workspace
import java.io.File

/** Project instructions and self-written lessons that are injected into the system prompt. */
object ProjectMemory {
    /** Files read in order; both are optional. Lessons are appended by the agent itself. */
    val FILES = listOf("AGENTS.md", ".agent/memory.md")
    const val MAX_CHARS = 20_000

    fun load(workspace: Workspace): String = FILES
        .map { File(workspace.root, it) }
        .filter { it.isFile }
        .joinToString("\n\n") { file ->
            val text = file.readText().take(MAX_CHARS)
            "<file path=\"${workspace.relative(file)}\">\n$text\n</file>"
        }
}

object SystemPrompt {
    fun build(workspace: Workspace, memory: String, skillIndex: String = ""): String = buildString {
        appendLine("You are a coding agent running on an Android phone. You help the user read, change and")
        appendLine("test code inside the workspace at ${workspace.root.path}.")
        appendLine()
        appendLine("Working rules:")
        appendLine("- Explore before editing: list_dir, grep and read_file first.")
        appendLine("- Prefer edit_file for small changes. Keep edits minimal and in the existing style.")
        appendLine("- After changing code, run the project's tests or build with the shell tool when possible.")
        appendLine("- Stay inside the workspace. Ask the user before destructive operations.")
        appendLine("- When you learn a durable lesson about this project, record it with append_memory.")
        appendLine("- Be concise. Report what you changed and what you verified.")
        if (skillIndex.isNotBlank()) {
            appendLine()
            appendLine("Skills (call load_skill with the name when one matches the task):")
            appendLine(skillIndex)
        }
        if (memory.isNotBlank()) {
            appendLine()
            appendLine("Project memory:")
            appendLine(memory)
        }
    }
}
