package dev.agent.core.tools

import java.io.File

/**
 * The directory the agent is allowed to touch. Every path the model supplies is
 * resolved against [root] and rejected if it ends up outside it, including via
 * symlinks, because canonicalization follows links.
 */
class Workspace(root: File) {
    val root: File = root.canonicalFile.also { it.mkdirs() }

    fun resolve(path: String): File {
        val candidate = File(root, path.ifBlank { "." }).canonicalFile
        val inside = candidate.toPath().startsWith(root.toPath())
        require(inside) { "Path '$path' is outside the workspace" }
        return candidate
    }

    /** Path relative to the workspace root, using forward slashes. */
    fun relative(file: File): String =
        file.canonicalFile.relativeTo(root).invariantSeparatorsPath.ifEmpty { "." }
}
