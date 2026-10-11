package dev.agent.core.tasks

import dev.agent.core.llm.AgentJson
import dev.agent.core.tools.Tool
import java.io.File
import java.util.UUID

/**
 * Stores tasks as `<project>/.agent/tasks/<id>/task.json`. Keeping them in the project means
 * they travel with the folder and survive app restarts.
 */
class TaskStore(projectRoot: File) {
    private val dir = File(projectRoot, ".agent/tasks")

    fun create(title: String, mode: TaskMode, now: Long = System.currentTimeMillis()): TaskRecord {
        val task = TaskRecord(
            id = UUID.randomUUID().toString().take(8),
            title = title.trim().ifBlank { "Untitled task" },
            mode = mode,
            status = TaskStatus.DONE,
            createdAt = now,
            updatedAt = now,
        )
        save(task)
        return task
    }

    fun save(task: TaskRecord) {
        val folder = File(dir, task.id).apply { mkdirs() }
        File(folder, FILE_NAME).writeText(AgentJson.encodeToString(TaskRecord.serializer(), task))
    }

    fun load(id: String): TaskRecord? {
        val file = File(dir, "$id/$FILE_NAME")
        if (!file.isFile) return null
        return try {
            AgentJson.decodeFromString(TaskRecord.serializer(), file.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Newest first. Unreadable task files are skipped rather than failing the whole list. */
    fun list(): List<TaskRecord> =
        dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.createdAt }

    companion object {
        const val FILE_NAME = "task.json"
    }
}

/** Decides which tools a task may use. Enforced here, not by asking the model nicely. */
object ToolPolicy {
    private val WRITE_TOOLS = setOf("write_file", "edit_file", "shell", "append_memory")

    fun toolsFor(mode: TaskMode, all: List<Tool>): List<Tool> = when (mode) {
        TaskMode.CODE -> all
        TaskMode.ASK -> all.filter { it.name !in WRITE_TOOLS && !it.requiresApproval }
    }
}
