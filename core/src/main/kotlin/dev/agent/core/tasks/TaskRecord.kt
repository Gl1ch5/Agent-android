package dev.agent.core.tasks

import dev.agent.core.llm.ApiMessage
import kotlinx.serialization.Serializable

/**
 * ASK answers questions about the project and can only read it.
 * CODE may also change files and run shell commands, subject to approval.
 */
@Serializable
enum class TaskMode { ASK, CODE }

@Serializable
enum class TaskStatus { RUNNING, DONE, FAILED, CANCELLED }

/** One file that differs between the start and end of a task. */
@Serializable
data class FileChange(
    val path: String,
    /** "added", "modified" or "deleted". */
    val status: String,
    /** Line-based diff; empty when the file is too large or binary to show. */
    val diff: String = "",
)

/**
 * A persisted unit of work inside a project. [history] is the model conversation, so a
 * follow-up message continues where the last run stopped, even after an app restart.
 */
@Serializable
data class TaskRecord(
    val id: String,
    val title: String,
    val mode: TaskMode,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val history: List<ApiMessage> = emptyList(),
    val changes: List<FileChange> = emptyList(),
    val error: String? = null,
)
