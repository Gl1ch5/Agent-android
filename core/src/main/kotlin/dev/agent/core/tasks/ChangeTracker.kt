package dev.agent.core.tasks

import java.io.File
import java.security.MessageDigest

/**
 * Records what a task changed on disk. Hashing every file catches changes made by any tool,
 * including shell commands, not only the file-edit tools.
 */
object ChangeTracker {
    const val MAX_TEXT_FILE_BYTES = 256 * 1024
    /** Total text kept in memory per snapshot. Files past this are compared by hash only. */
    const val MAX_TEXT_BUDGET_BYTES = 8 * 1024 * 1024
    const val MAX_FILES = 20_000
    private const val MAX_DIFF_CELLS = 2_000_000

    private val SKIP_DIRS = setOf(".git", "build", "node_modules", ".gradle", ".idea")

    class Entry(val hash: String, val text: String?)

    fun capture(root: File): Map<String, Entry> {
        val result = mutableMapOf<String, Entry>()
        var budget = MAX_TEXT_BUDGET_BYTES
        val walk = root.walkTopDown().onEnter { it == root || it.name !in SKIP_DIRS }
        for (file in walk) {
            if (!file.isFile) continue
            if (result.size >= MAX_FILES) break
            val rel = file.relativeTo(root).invariantSeparatorsPath
            // Large files are fingerprinted by size and mtime, never read whole, so a folder
            // with videos or APKs stays cheap to snapshot.
            if (file.length() > MAX_TEXT_FILE_BYTES) {
                result[rel] = Entry("${file.length()}:${file.lastModified()}", null)
                continue
            }
            val bytes = file.readBytes()
            val hash = sha256(bytes)
            val text = if (bytes.size <= budget && isText(bytes)) {
                budget -= bytes.size
                String(bytes, Charsets.UTF_8)
            } else {
                null
            }
            result[rel] = Entry(hash, text)
        }
        return result
    }

    fun diff(before: Map<String, Entry>, after: Map<String, Entry>): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        for ((path, now) in after) {
            val old = before[path]
            when {
                old == null -> changes += FileChange(path, "added", lineDiff("", now.text))
                old.hash != now.hash -> changes += FileChange(path, "modified", lineDiff(old.text, now.text))
            }
        }
        for (path in before.keys - after.keys) {
            changes += FileChange(path, "deleted", lineDiff(before.getValue(path).text, ""))
        }
        return changes.sortedBy { it.path }
    }

    /** Lines removed as "-", lines added as "+", in order. Empty when either side is unknown. */
    internal fun lineDiff(oldText: String?, newText: String?): String {
        if (oldText == null || newText == null) return ""
        val a = splitLines(oldText)
        val b = splitLines(newText)
        if (a.size.toLong() * b.size > MAX_DIFF_CELLS) return "(file too large to show a diff)"

        // Longest common subsequence table, then walk it back to produce the edit script.
        val width = b.size + 1
        val lcs = IntArray((a.size + 1) * width)
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                lcs[i * width + j] = if (a[i] == b[j]) {
                    lcs[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
                }
            }
        }
        val out = StringBuilder()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { i++; j++ }
                j < b.size && (i == a.size || lcs[i * width + j + 1] >= lcs[(i + 1) * width + j]) -> {
                    out.append("+ ").append(b[j]).append('\n'); j++
                }
                else -> { out.append("- ").append(a[i]).append('\n'); i++ }
            }
        }
        return out.toString().trimEnd()
    }

    /** An empty file has no lines; "".lines() would report one blank line instead. */
    private fun splitLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()

    private fun isText(bytes: ByteArray): Boolean =
        bytes.take(8000).none { it == 0.toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
