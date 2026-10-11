package dev.agent.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileToolsTest {
    private val ws = Workspace(createTempDir("tools-test"))

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            put(k, when (v) {
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            })
        }
    }

    @Test
    fun readFileNumbersLines() = kotlinx.coroutines.test.runTest {
        File(ws.root, "a.txt").writeText("one\ntwo\nthree\n")
        val out = ReadFileTool().execute(args("path" to "a.txt"), ws)
        assertFalse(out.isError)
        assertContains(out.text, "1\tone")
        assertContains(out.text, "3\tthree")
    }

    @Test
    fun editFailsWhenOldStringIsAmbiguous() = kotlinx.coroutines.test.runTest {
        File(ws.root, "b.txt").writeText("x = 1\nx = 1\n")
        val out = EditFileTool().execute(args("path" to "b.txt", "old_string" to "x = 1", "new_string" to "x = 2"), ws)
        assertTrue(out.isError)
        assertEquals("x = 1\nx = 1\n", File(ws.root, "b.txt").readText())
    }

    @Test
    fun editReplacesUniqueMatch() = kotlinx.coroutines.test.runTest {
        File(ws.root, "c.txt").writeText("alpha\nbeta\n")
        val out = EditFileTool().execute(args("path" to "c.txt", "old_string" to "beta", "new_string" to "gamma"), ws)
        assertFalse(out.isError)
        assertEquals("alpha\ngamma\n", File(ws.root, "c.txt").readText())
    }

    @Test
    fun editReplaceAllReplacesEveryMatch() = kotlinx.coroutines.test.runTest {
        File(ws.root, "d.txt").writeText("x x x")
        val out = EditFileTool().execute(
            args("path" to "d.txt", "old_string" to "x", "new_string" to "y", "replace_all" to true), ws,
        )
        assertFalse(out.isError)
        assertEquals("y y y", File(ws.root, "d.txt").readText())
    }

    @Test
    fun grepFindsMatchesWithLocation() = kotlinx.coroutines.test.runTest {
        File(ws.root, "src").mkdirs()
        File(ws.root, "src/A.kt").writeText("fun hello() {}\nval z = 0\n")
        val out = GrepTool().execute(args("pattern" to "hello"), ws)
        assertContains(out.text, "src/A.kt:1:fun hello() {}")
    }

    @Test
    fun shellRunsInsideWorkspace() = kotlinx.coroutines.test.runTest {
        val out = ShellTool("/bin/sh").execute(args("command" to "pwd"), ws)
        assertContains(out.text, ws.root.canonicalPath)
        assertFalse(out.isError)
    }

    @Test
    fun shellReportsFailingExitCode() = kotlinx.coroutines.test.runTest {
        val out = ShellTool("/bin/sh").execute(args("command" to "echo boom; exit 3"), ws)
        assertTrue(out.isError)
        assertContains(out.text, "exit code: 3")
        assertContains(out.text, "boom")
    }
}
