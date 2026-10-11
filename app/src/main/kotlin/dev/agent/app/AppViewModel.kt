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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One row in a project's chat transcript. */
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

/** Transcript and agent for one project. Kept in memory while the app process lives. */
private class ProjectChat(val project: Project) {
    val items = MutableStateFlow<List<ChatItem>>(emptyList())
    val busy = MutableStateFlow(false)
    var agent: Agent? = null

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Text -> append(ChatItem.Assistant(newId(), event.text))
            is AgentEvent.ToolStarted -> append(ChatItem.Tool(event.id, event.name, event.input.toString()))
            is AgentEvent.ToolFinished -> items.update { list ->
                list.map { item ->
                    if (item.id == event.id && item is ChatItem.Tool) {
                        item.copy(output = event.output, isError = event.isError)
                    } else item
                }
            }
            is AgentEvent.Error -> append(ChatItem.Error(newId(), event.message))
            AgentEvent.Done -> Unit
        }
    }

    fun append(item: ChatItem) = items.update { it + item }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val chats = mutableMapOf<String, ProjectChat>()

    private val _projects = MutableStateFlow(settings.projects)
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _open = MutableStateFlow<ProjectChat?>(null)

    /** The project currently open, or null on the project list. */
    val openProject: StateFlow<Project?> = _open.map { it?.project }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val items: StateFlow<List<ChatItem>> = _open.flatMapLatest { it?.items ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val busy: StateFlow<Boolean> = _open.flatMapLatest { it?.busy ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** One-line message for the UI, for example after a failed folder import. */
    val notice = MutableStateFlow<String?>(null)

    fun createProject(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val dir = File(getApplication<Application>().filesDir, "projects/${slug(clean)}-${UUID.randomUUID().toString().take(6)}")
        dir.mkdirs()
        addProject(Project(UUID.randomUUID().toString(), clean, dir.path))
    }

    fun addFolder(path: String) {
        val dir = File(path)
        if (!dir.isDirectory) {
            notice.value = "Folder not found: $path"
            return
        }
        if (_projects.value.any { it.path == dir.canonicalPath }) {
            notice.value = "This folder is already a project"
            return
        }
        addProject(Project(UUID.randomUUID().toString(), dir.name.ifBlank { dir.path }, dir.canonicalPath))
    }

    fun openProject(project: Project) {
        _open.value = chats.getOrPut(project.id) { ProjectChat(project) }
    }

    fun closeProject() {
        _open.value = null
    }

    fun removeProject(project: Project) {
        chats.remove(project.id)
        if (_open.value?.project?.id == project.id) _open.value = null
        _projects.update { list -> list.filterNot { it.id == project.id }.also { settings.projects = it } }
    }

    fun send(text: String) {
        val chat = _open.value ?: return
        val message = text.trim()
        if (message.isEmpty() || chat.busy.value) return
        chat.append(ChatItem.User(newId(), message))
        chat.busy.value = true
        viewModelScope.launch {
            try {
                val session = chat.agent ?: createAgent(chat).also { chat.agent = it }
                session.send(message)
            } catch (e: IllegalStateException) {
                chat.append(ChatItem.Error(newId(), e.message ?: "Configuration error"))
            } finally {
                chat.busy.value = false
            }
        }
    }

    fun newChat() {
        val chat = _open.value ?: return
        if (chat.busy.value) return
        chat.agent = null
        chat.items.value = emptyList()
    }

    private fun addProject(project: Project) {
        _projects.update { list -> (list + project).also { settings.projects = it } }
        openProject(project)
    }

    private fun createAgent(chat: ProjectChat): Agent {
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
            ?: settings.providers.first()
        check(provider.selectedModel.isNotBlank()) { "Choose a model for ${provider.name} in Settings." }
        check(provider.kind == ProviderKind.ANTHROPIC || provider.baseUrl.isNotBlank()) {
            "Set the base URL for ${provider.name} in Settings."
        }
        val workspace = Workspace(File(chat.project.path))
        return Agent(
            llm = provider.createClient(),
            tools = defaultTools { SkillLoader.load(workspace) },
            workspace = workspace,
            // Permissions are off for now: every tool call runs without asking.
            // To bring the approval dialog back, replace this gate with one that asks the user.
            approval = { _, _ -> true },
            systemPrompt = {
                SystemPrompt.build(
                    workspace,
                    memory = ProjectMemory.load(workspace),
                    skillIndex = SkillLoader.index(SkillLoader.load(workspace)),
                )
            },
            onEvent = chat::onEvent,
        )
    }

    private fun slug(name: String) = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "project" }

    private fun newId() = UUID.randomUUID().toString()
}
