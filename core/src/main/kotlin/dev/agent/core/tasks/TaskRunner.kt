package dev.agent.core.tasks

import dev.agent.core.agent.Agent
import dev.agent.core.agent.AgentEvent
import dev.agent.core.llm.LlmClient
import dev.agent.core.tools.Tool
import dev.agent.core.tools.Workspace
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Runs one turn of a task: restores its history, runs the agent with the tools its mode
 * allows, then saves the new history and the file changes it made.
 */
class TaskRunner(
    private val llm: LlmClient,
    private val allTools: List<Tool>,
    private val store: TaskStore,
    private val projectRoot: File,
    private val systemPrompt: (Workspace) -> String,
    private val onEvent: (taskId: String, AgentEvent) -> Unit = { _, _ -> },
    /** Asked before a tool that changes state runs. Returns true to allow it. */
    private val approve: suspend (taskId: String, toolName: String, input: JsonObject) -> Boolean = { _, _, _ -> true },
) {
    /** Sends [message] as the next turn of [task]. Returns the saved record. */
    suspend fun run(task: TaskRecord, message: String): TaskRecord {
        val workspace = Workspace(projectRoot)
        val before = ChangeTracker.capture(workspace.root)
        store.save(task.copy(status = TaskStatus.RUNNING, error = null, updatedAt = now()))

        var error: String? = null
        val agent = Agent(
            llm = llm,
            tools = ToolPolicy.toolsFor(task.mode, allTools),
            workspace = workspace,
            // ASK tasks never see write tools; CODE tasks ask before each state-changing call.
            approval = { name, input -> approve(task.id, name, input) },
            systemPrompt = { modePrompt(task.mode, systemPrompt(workspace)) },
            onEvent = { event ->
                if (event is AgentEvent.Error) error = event.message
                onEvent(task.id, event)
            },
            initialHistory = task.history,
        )

        val finished = try {
            agent.send(message)
            val after = ChangeTracker.capture(workspace.root)
            task.copy(
                status = if (error == null) TaskStatus.DONE else TaskStatus.FAILED,
                history = agent.messages,
                changes = ChangeTracker.diff(before, after),
                error = error,
                updatedAt = now(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            store.save(task.copy(status = TaskStatus.CANCELLED, updatedAt = now()))
            throw e
        }
        store.save(finished)
        return finished
    }

    private fun modePrompt(mode: TaskMode, base: String): String = when (mode) {
        TaskMode.CODE -> base
        TaskMode.ASK -> base + "\n\nThis is an ASK task: answer questions about the project. " +
            "You can read files and search, but you cannot change files or run commands."
    }

    private fun now() = System.currentTimeMillis()
}
