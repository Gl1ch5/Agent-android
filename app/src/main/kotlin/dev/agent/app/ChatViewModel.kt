package dev.agent.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agent.core.agent.Agent
import dev.agent.core.agent.AgentEvent
import dev.agent.core.agent.ProjectMemory
import dev.agent.core.agent.SystemPrompt
import dev.agent.core.provider.ProviderKind
import dev.agent.core.provider.createClient
import dev.agent.core.skills.SkillLoader
import dev.agent.core.tools.Workspace
import dev.agent.core.tools.defaultTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** One row in the chat transcript. */
sealed interface ChatItem {
    val id: String

    data class User(override val id: String, val text: String) : ChatItem
    data class Assistant(override val id: String, val text: String) : ChatItem
    data class Tool(
        override val id: String,
        val name: String,
        val input: String,
        val output: String? = null,
        val isError: Boolean = false,
    ) : ChatItem
    data class Error(override val id: String, val text: String) : ChatItem
}

/** A tool call waiting for the user to allow or deny it. */
data class PendingApproval(val toolName: String, val input: String)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    val workspace = Workspace(File(application.filesDir, "workspace"))

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private var agent: Agent? = null

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _busy.value) return
        append(ChatItem.User(newId(), message))
        _busy.value = true
        viewModelScope.launch {
            try {
                val session = agent ?: createAgent().also { agent = it }
                session.send(message)
            } catch (e: IllegalStateException) {
                append(ChatItem.Error(newId(), e.message ?: "Configuration error"))
            } finally {
                _busy.value = false
            }
        }
    }

    /** Answers the pending approval dialog. */
    fun resolveApproval(allowed: Boolean) {
        pendingDecision?.complete(allowed)
        pendingDecision = null
        _approval.value = null
    }

    fun newChat() {
        if (_busy.value) return
        agent = null
        _items.value = emptyList()
    }

    private fun createAgent(): Agent {
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
            ?: settings.providers.first()
        check(provider.selectedModel.isNotBlank()) { "Choose a model for ${provider.name} in Settings." }
        check(provider.kind == ProviderKind.ANTHROPIC || provider.baseUrl.isNotBlank()) {
            "Set the base URL for ${provider.name} in Settings."
        }
        val llm = provider.createClient()
        val tools = defaultTools { SkillLoader.load(workspace) }
        return Agent(
            llm = llm,
            tools = tools,
            workspace = workspace,
            approval = ::askApproval,
            systemPrompt = {
                SystemPrompt.build(
                    workspace,
                    memory = ProjectMemory.load(workspace),
                    skillIndex = SkillLoader.index(SkillLoader.load(workspace)),
                )
            },
            onEvent = ::onAgentEvent,
        )
    }

    private suspend fun askApproval(name: String, input: JsonObject): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingDecision = decision
        _approval.value = PendingApproval(name, input.toString())
        return decision.await()
    }

    private fun onAgentEvent(event: AgentEvent) {
        // Events can come from the agent's coroutine; StateFlow updates are thread-safe.
        when (event) {
            is AgentEvent.Text -> append(ChatItem.Assistant(newId(), event.text))
            is AgentEvent.ToolStarted -> append(
                ChatItem.Tool(event.id, event.name, event.input.toString()),
            )
            is AgentEvent.ToolFinished -> updateItem(event.id) { tool ->
                (tool as? ChatItem.Tool)?.copy(output = event.output, isError = event.isError) ?: tool
            }
            is AgentEvent.Error -> append(ChatItem.Error(newId(), event.message))
            AgentEvent.Done -> Unit
        }
    }

    private fun append(item: ChatItem) = _items.update { it + item }

    private fun updateItem(id: String, transform: (ChatItem) -> ChatItem) =
        _items.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun newId() = UUID.randomUUID().toString()
}
