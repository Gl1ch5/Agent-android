package dev.agent.core.agent

import dev.agent.core.llm.ApiMessage
import dev.agent.core.llm.ContentBlock
import dev.agent.core.llm.LlmClient
import dev.agent.core.tools.Tool
import dev.agent.core.tools.ToolOutput
import dev.agent.core.tools.Workspace
import kotlinx.serialization.json.JsonObject

/** Progress events the UI renders while the agent works. */
sealed interface AgentEvent {
    data class Text(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val input: JsonObject) : AgentEvent
    data class ToolFinished(val id: String, val name: String, val output: String, val isError: Boolean) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data object Done : AgentEvent
}

/** Asks the user to allow a tool call that changes state. Returns true to allow it. */
typealias ApprovalGate = suspend (toolName: String, input: JsonObject) -> Boolean

/**
 * Runs the model/tool loop. Conversation history is kept between [send] calls so one
 * Agent instance is one chat session.
 */
class Agent(
    private val llm: LlmClient,
    private val tools: List<Tool>,
    private val workspace: Workspace,
    private val approval: ApprovalGate,
    private val systemPrompt: () -> String,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val maxSteps: Int = 40,
) {
    private val history = mutableListOf<ApiMessage>()
    private val toolsByName = tools.associateBy { it.name }

    /** Snapshot of the conversation, for persistence or display. */
    val messages: List<ApiMessage> get() = history.toList()

    /** Sends a user message and runs model/tool steps until the model stops calling tools. */
    suspend fun send(userText: String) {
        history += ApiMessage("user", listOf(ContentBlock.Text(userText)))
        try {
            repeat(maxSteps) {
                val response = llm.complete(systemPrompt(), history, tools.map { it.spec() })
                history += ApiMessage("assistant", response.blocks)
                response.blocks.filterIsInstance<ContentBlock.Text>().forEach {
                    if (it.text.isNotBlank()) onEvent(AgentEvent.Text(it.text))
                }

                val calls = response.blocks.filterIsInstance<ContentBlock.ToolUse>()
                if (calls.isEmpty()) {
                    onEvent(AgentEvent.Done)
                    return
                }
                val results = calls.map { runTool(it) }
                history += ApiMessage("user", results)
            }
            onEvent(AgentEvent.Error("Stopped after $maxSteps steps without a final answer."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onEvent(AgentEvent.Error(e.message ?: e::class.java.simpleName))
        }
    }

    private suspend fun runTool(call: ContentBlock.ToolUse): ContentBlock.ToolResult {
        onEvent(AgentEvent.ToolStarted(call.id, call.name, call.input))
        val output = execute(call)
        onEvent(AgentEvent.ToolFinished(call.id, call.name, output.text, output.isError))
        return ContentBlock.ToolResult(call.id, output.text, output.isError)
    }

    private suspend fun execute(call: ContentBlock.ToolUse): ToolOutput {
        val tool = toolsByName[call.name] ?: return ToolOutput("Unknown tool '${call.name}'", isError = true)
        if (tool.requiresApproval && !approval(call.name, call.input)) {
            return ToolOutput("The user denied this ${call.name} call. Ask what they want instead.", isError = true)
        }
        return try {
            tool.execute(call.input, workspace)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput("${e::class.java.simpleName}: ${e.message}", isError = true)
        }
    }
}
