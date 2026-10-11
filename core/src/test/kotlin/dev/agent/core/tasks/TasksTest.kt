package dev.agent.core.tasks

import dev.agent.core.tools.ListDirTool
import dev.agent.core.tools.ReadFileTool
import dev.agent.core.tools.ShellTool
import dev.agent.core.tools.WriteFileTool
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasksTest {
    private fun tempProject(): File = createTempDirectory("tasks-test").toFile()

    @Test
    fun `line diff shows only removed and added lines`() {
        val diff = ChangeTracker.lineDiff("a\nb\nc", "a\nB\nc")
        assertEquals("- b\n+ B", diff)
    }

    @Test
    fun `added file has no removed lines`() {
        assertEquals("+ one\n+ two", ChangeTracker.lineDiff("", "one\ntwo"))
    }

    @Test
    fun `capture and diff detect add, modify and delete, skipping build dirs`() {
        val root = tempProject()
        File(root, "keep.txt").writeText("old")
        File(root, "gone.txt").writeText("bye")
        File(root, "build").mkdirs()
        File(root, "build/out.txt").writeText("ignored")

        val before = ChangeTracker.capture(root)
        File(root, "keep.txt").writeText("new")
        File(root, "gone.txt").delete()
        File(root, "fresh.txt").writeText("hello")
        val changes = ChangeTracker.diff(before, ChangeTracker.capture(root)).associateBy { it.path }

        assertEquals("modified", changes.getValue("keep.txt").status)
        assertEquals("deleted", changes.getValue("gone.txt").status)
        assertEquals("added", changes.getValue("fresh.txt").status)
        assertTrue("build/out.txt" !in changes)
    }

    @Test
    fun `large files are tracked without a text diff`() {
        val root = tempProject()
        val big = File(root, "video.bin")
        big.writeBytes(ByteArray(ChangeTracker.MAX_TEXT_FILE_BYTES + 1))

        val before = ChangeTracker.capture(root)
        assertEquals(null, before.getValue("video.bin").text)
        big.writeBytes(ByteArray(ChangeTracker.MAX_TEXT_FILE_BYTES + 2))
        val change = ChangeTracker.diff(before, ChangeTracker.capture(root)).single()

        assertEquals("modified", change.status)
        assertEquals("", change.diff)
    }

    @Test
    fun `ask mode drops every tool that can change state`() {
        val tools = listOf(ListDirTool(), ReadFileTool(), WriteFileTool(), ShellTool())
        val names = ToolPolicy.toolsFor(TaskMode.ASK, tools).map { it.name }
        assertEquals(listOf("list_dir", "read_file"), names)
        assertEquals(tools.size, ToolPolicy.toolsFor(TaskMode.CODE, tools).size)
    }

    @Test
    fun `store round-trips a task and lists newest first`() {
        val store = TaskStore(tempProject())
        val older = store.create("first", TaskMode.ASK, now = 1L)
        val newer = store.create("second", TaskMode.CODE, now = 2L)

        assertEquals(newer, store.load(newer.id))
        assertEquals(listOf(newer.id, older.id), store.list().map { it.id })
    }
}
