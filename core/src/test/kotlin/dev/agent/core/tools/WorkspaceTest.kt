package dev.agent.core.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class WorkspaceTest {
    private val root = createTempDir("ws-test")

    @Test
    fun resolvesPathsInsideRoot() {
        val ws = Workspace(root)
        assertEquals(File(ws.root, "src/Main.kt").canonicalPath, ws.resolve("src/Main.kt").canonicalPath)
    }

    @Test
    fun rejectsParentTraversal() {
        val ws = Workspace(root)
        assertFailsWith<IllegalArgumentException> { ws.resolve("../escape.txt") }
        assertFailsWith<IllegalArgumentException> { ws.resolve("a/../../escape.txt") }
    }

    @Test
    fun rejectsSymlinkEscape() {
        val outside = createTempDir("outside")
        val ws = Workspace(root)
        val link = File(ws.root, "link").toPath()
        java.nio.file.Files.createSymbolicLink(link, outside.toPath())
        assertFailsWith<IllegalArgumentException> { ws.resolve("link/secret.txt") }
    }
}
