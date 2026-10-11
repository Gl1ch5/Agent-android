package dev.agent.app

import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import dev.agent.core.agent.AgentEvent
import dev.agent.core.agent.ProjectMemory
import dev.agent.core.agent.SystemPrompt
import dev.agent.core.llm.AgentJson
import dev.agent.core.llm.OpenAiClient
import dev.agent.core.mcp.McpRegistry
import dev.agent.core.mcp.McpServerConfig
import dev.agent.core.provider.ProviderConfig
import dev.agent.core.provider.ProviderKind
import dev.agent.core.provider.createClient
import dev.agent.core.skills.SkillLoader
import dev.agent.core.tasks.TaskMode
import dev.agent.core.tasks.TaskRecord
import dev.agent.core.tasks.TaskRunner
import dev.agent.core.tasks.TaskStatus
import dev.agent.core.tasks.TaskStore
import dev.agent.core.tools.Workspace
import dev.agent.core.tools.defaultTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only object the web UI can call (as `window.Android`). Requests are JSON with an `id`
 * and an `op`; replies go back through `window.__reply(id, result)`. Agent progress goes
 * through `window.__event(event)`. Every reply is a JSON object, and failures carry `error`.
 */
class AgentBridge(
    private val context: Context,
    private val host: Host,
    private val scope: CoroutineScope,
    /** Runs a JavaScript snippet in the page. Must be safe to call from any thread. */
    private val runJs: (String) -> Unit,
) {
    /** Platform calls that need the activity. */
    interface Host {
        fun pickFolder(onResult: (JsonObject) -> Unit)
        fun allFilesGranted(): Boolean
        fun openAllFilesSettings()
    }

    private val settings = AppSettings(context)
    private val runs = ConcurrentHashMap<String, Job>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /** The approval events still waiting for an answer, by approval id. The task view replays them on open. */
    private val pendingApprovals = ConcurrentHashMap<String, JsonObject>()

    @JavascriptInterface
    fun request(raw: String) {
        val req = AgentJson.parseToJsonElement(raw).jsonObject
        val id = req.getValue("id").jsonPrimitive.content
        val op = req.getValue("op").jsonPrimitive.content
        if (op == "pickFolder") {
            host.pickFolder { reply(id, it) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = try {
                handle(op, req)
            } catch (e: Exception) {
                failure(e.message ?: e::class.java.simpleName)
            } catch (e: LinkageError) {
                // A library built for a newer Android or Java can fail to link at call time (JGit, MCP).
                failure("Not supported on this Android version: ${e.message ?: e::class.java.simpleName}")
            }
            reply(id, result)
        }
    }

    // Block body, not an expression body: the branches use `return` for early failures.
    private suspend fun handle(op: String, req: JsonObject): JsonObject {
        return when (op) {
            "state" -> buildJsonObject {
                put("allFiles", allFilesOk())
                put("projects", AgentJson.encodeToJsonElement(ListSerializer(Project.serializer()), settings.projects))
                put("providers", providersJson())
                put("activeId", settings.activeProviderId)
            put("mcpServers", AgentJson.encodeToJsonElement(ListSerializer(McpServerConfig.serializer()), settings.mcpServers))
            put("hasGithubToken", settings.githubToken.isNotEmpty())
            }
            "createProject" -> {
                val name = req.text("name").trim()
                if (name.isEmpty()) return failure("Enter a project name.")
                val dir = File(context.filesDir, "projects/${slug(name)}-${UUID.randomUUID().toString().take(6)}")
                dir.mkdirs()
                projectJson(addProject(Project(UUID.randomUUID().toString(), name, dir.path)))
            }
            "addFolder" -> {
                val dir = File(req.text("path"))
                if (!dir.isDirectory) return failure("Folder not found: ${dir.path}")
                val canonical = dir.canonicalPath
                val inAppStorage = canonical.startsWith(context.filesDir.canonicalPath)
                if (!inAppStorage && !allFilesOk()) {
                    return failure("Grant all-files access first, so projects can use folders outside the app.")
                }
                if (settings.projects.any { it.path == canonical }) return failure("This folder is already a project.")
                projectJson(addProject(Project(UUID.randomUUID().toString(), dir.name.ifBlank { canonical }, canonical)))
            }
            "removeProject" -> {
                val id = req.text("id")
                settings.projects = settings.projects.filterNot { it.id == id }
                buildJsonObject { put("ok", true) }
            }
            "openAllFiles" -> {
                host.openAllFilesSettings()
                buildJsonObject { put("ok", true) }
            }
            "saveProvider" -> {
                val provider = AgentJson.decodeFromJsonElement(ProviderConfig.serializer(), req.getValue("provider"))
                if (provider.name.isBlank()) return failure("Enter a name for this provider.")
                val list = settings.providers.filterNot { it.id == provider.id } + provider
                settings.providers = list
                if (settings.providers.none { it.id == settings.activeProviderId }) settings.activeProviderId = provider.id
                buildJsonObject { put("providers", providersJson()) }
            }
            "deleteProvider" -> {
                val remaining = settings.providers.filterNot { it.id == req.text("id") }
                if (remaining.isEmpty()) return failure("Keep at least one provider.")
                settings.providers = remaining
                if (remaining.none { it.id == settings.activeProviderId }) settings.activeProviderId = remaining.first().id
                buildJsonObject { put("providers", providersJson()) }
            }
            "setActive" -> {
                settings.activeProviderId = req.text("id")
                buildJsonObject { put("activeId", settings.activeProviderId) }
            }
            "fetchModels" -> {
                val provider = AgentJson.decodeFromJsonElement(ProviderConfig.serializer(), req.getValue("provider"))
                if (provider.kind != ProviderKind.OPENAI_COMPATIBLE) {
                    return failure("Anthropic does not expose a model list here. Enter the model ID by hand.")
                }
                val models = OpenAiClient.listModels(provider.baseUrl, provider.apiKey)
                buildJsonObject { put("models", buildJsonArray { models.forEach { add(JsonPrimitive(it)) } }) }
            }
            "tasks" -> {
                val project = projectById(req.text("projectId")) ?: return failure("Project not found.")
                val store = TaskStore(File(project.path))
                val tasks = store.list().map { reconcile(store, it) }
                buildJsonObject { put("tasks", buildJsonArray { tasks.forEach { add(summary(it)) } }) }
            }
            "task" -> {
                val project = projectById(req.text("projectId")) ?: return failure("Project not found.")
                val task = TaskStore(File(project.path)).load(req.text("taskId")) ?: return failure("Task not found.")
                val record = AgentJson.encodeToJsonElement(TaskRecord.serializer(), reconcile(TaskStore(File(project.path)), task))
                    .jsonObject
                // Approvals raised while the task view was closed wait here until the page opens the task.
                val pending = pendingApprovals.values.filter { it["taskId"]?.jsonPrimitive?.content == task.id }
                JsonObject(record + ("pendingApprovals" to JsonArray(pending)))
            }
            "newTask" -> {
                val project = projectById(req.text("projectId")) ?: return failure("Project not found.")
                val text = req.text("text").trim()
                if (text.isEmpty()) return failure("Describe the task first.")
                val mode = TaskMode.valueOf(req.text("mode").ifBlank { "CODE" })
                val task = TaskStore(File(project.path)).create(title = text.lineSequence().first().take(80), mode = mode)
                start(project, task, text)
                summary(task)
            }
            "followUp" -> {
                val project = projectById(req.text("projectId")) ?: return failure("Project not found.")
                val text = req.text("text").trim()
                if (text.isEmpty()) return failure("Write a message first.")
                val task = TaskStore(File(project.path)).load(req.text("taskId")) ?: return failure("Task not found.")
                if (runs.containsKey(task.id)) return failure("This task is still running.")
                start(project, task, text)
                summary(task)
            }
            "saveGithubToken" -> {
                settings.githubToken = req.text("token").trim()
                buildJsonObject { put("hasGithubToken", settings.githubToken.isNotEmpty()) }
            }
            "cloneRepo" -> {
                val url = req.text("url").trim()
                if (!url.startsWith("https://")) return failure("Enter an https:// repository URL.")
                val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
                val dir = File(context.filesDir, "projects/${slug(name)}-${UUID.randomUUID().toString().take(6)}")
                try {
                    GitHubOps.clone(url, dir, settings.githubToken)
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    return failure("Clone failed: ${e.message ?: e::class.java.simpleName}")
                }
                projectJson(addProject(Project(UUID.randomUUID().toString(), name, dir.path)))
            }
            "openPullRequest" -> {
                val project = projectById(req.text("projectId")) ?: return failure("Project not found.")
                val task = TaskStore(File(project.path)).load(req.text("taskId")) ?: return failure("Task not found.")
                if (settings.githubToken.isBlank()) return failure("Add a GitHub token in Settings first.")
                if (task.changes.isEmpty()) return failure("This task has no file changes.")
                val body = "Changes made by the agent in task \"${task.title}\"."
                val branch = GitHubOps.branchFor(task.title, task.id)
                val url = GitHubOps.openPullRequest(File(project.path), branch, task.title, body, settings.githubToken)
                buildJsonObject { put("url", url) }
            }
            "saveMcpServers" -> {
                val servers = AgentJson.decodeFromJsonElement(ListSerializer(McpServerConfig.serializer()), req.getValue("servers"))
                val bad = servers.firstOrNull { !it.url.startsWith("http://") && !it.url.startsWith("https://") }
                if (bad != null) return failure("MCP server URL must start with http:// or https://: ${bad.url}")
                settings.mcpServers = servers
                buildJsonObject { put("mcpServers", AgentJson.encodeToJsonElement(ListSerializer(McpServerConfig.serializer()), servers)) }
            }
            "approve" -> {
                // Unknown or already answered ids are ignored: the card may outlive the run.
                approvals[req.text("approvalId")]?.complete(req.getValue("allow").jsonPrimitive.boolean)
                buildJsonObject { put("ok", true) }
            }
            "cancel" -> {
                runs[req.text("taskId")]?.cancel()
                buildJsonObject { put("ok", true) }
            }
            else -> failure("Unknown request: $op")
        }
    }

    /** A RUNNING task with no live job was cut off by a process restart. Mark it instead of hanging. */
    private fun reconcile(store: TaskStore, task: TaskRecord): TaskRecord {
        if (task.status != TaskStatus.RUNNING || runs.containsKey(task.id)) return task
        val fixed = task.copy(status = TaskStatus.FAILED, error = "Interrupted: the app stopped during this run.")
        store.save(fixed)
        return fixed
    }

    private fun start(project: Project, task: TaskRecord, text: String) {
        val root = File(project.path)
        val store = TaskStore(root)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                runner(project).run(task, text)
            } catch (e: CancellationException) {
                // The runner has already saved the task as CANCELLED.
            } catch (e: Exception) {
                store.save(task.copy(status = TaskStatus.FAILED, error = e.message ?: "Run failed", updatedAt = System.currentTimeMillis()))
            } finally {
                runs.remove(task.id)
                RunService.end(context)
                emit(buildJsonObject {
                    put("type", "taskFinished")
                    put("projectId", project.id)
                    put("taskId", task.id)
                })
            }
        }
        runs[task.id] = job
        RunService.begin(context)
        job.start()
    }

    private suspend fun runner(project: Project): TaskRunner {
        val provider = settings.providers.firstOrNull { it.id == settings.activeProviderId }
            ?: settings.providers.first()
        check(provider.selectedModel.isNotBlank()) { "Choose a model for ${provider.name} in Settings." }
        val root = File(project.path)
        return TaskRunner(
            llm = provider.createClient(),
            allTools = defaultTools { SkillLoader.load(Workspace(root)) } + McpRegistry.load(settings.mcpServers),
            store = TaskStore(root),
            projectRoot = root,
            systemPrompt = { ws ->
                SystemPrompt.build(
                    ws,
                    memory = ProjectMemory.load(ws),
                    skillIndex = SkillLoader.index(SkillLoader.load(ws)),
                )
            },
            onEvent = { taskId, event -> emit(eventJson(project.id, taskId, event)) },
            approve = { taskId, tool, input -> requestApproval(project.id, taskId, tool, input) },
        )
    }

    /** Pauses the run until the page answers. Cancelling the run cancels the wait too. */
    private suspend fun requestApproval(projectId: String, taskId: String, tool: String, input: JsonObject): Boolean {
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<Boolean>()
        approvals[id] = answer
        val event = buildJsonObject {
            put("type", "approval")
            put("projectId", projectId)
            put("taskId", taskId)
            put("approvalId", id)
            put("tool", tool)
            put("input", input.toString())
        }
        pendingApprovals[id] = event
        try {
            emit(event)
            return answer.await()
        } finally {
            approvals.remove(id)
            pendingApprovals.remove(id)
        }
    }

    private fun eventJson(projectId: String, taskId: String, event: AgentEvent): JsonObject = buildJsonObject {
        put("type", "agent")
        put("projectId", projectId)
        put("taskId", taskId)
        when (event) {
            is AgentEvent.Text -> {
                put("kind", "text")
                put("text", event.text)
            }
            is AgentEvent.ToolStarted -> {
                put("kind", "toolStarted")
                put("id", event.id)
                put("name", event.name)
                put("input", event.input.toString())
            }
            is AgentEvent.ToolFinished -> {
                put("kind", "toolFinished")
                put("id", event.id)
                put("output", event.output)
                put("isError", event.isError)
            }
            is AgentEvent.Error -> {
                put("kind", "error")
                put("text", event.message)
            }
            AgentEvent.Done -> put("kind", "done")
        }
    }

    private fun summary(task: TaskRecord): JsonObject = buildJsonObject {
        put("id", task.id)
        put("title", task.title)
        put("mode", task.mode.name)
        put("status", task.status.name)
        put("updatedAt", task.updatedAt)
        put("changes", task.changes.size)
        put("error", task.error)
    }

    private fun addProject(project: Project): Project {
        settings.projects = settings.projects + project
        return project
    }

    private fun projectById(id: String): Project? = settings.projects.firstOrNull { it.id == id }

    private fun projectJson(project: Project): JsonObject =
        AgentJson.encodeToJsonElement(Project.serializer(), project).jsonObject

    private fun providersJson() =
        AgentJson.encodeToJsonElement(ListSerializer(ProviderConfig.serializer()), settings.providers)

    /** Scoped storage is only a problem below Android 11, and only outside the app's own folder. */
    private fun allFilesOk(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || host.allFilesGranted()

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun reply(id: String, result: JsonObject) {
        runJs("window.__reply(${JsonPrimitive(id)}, $result)")
    }

    private fun emit(event: JsonObject) {
        runJs("window.__event($event)")
    }

    private fun failure(message: String) = buildJsonObject { put("error", message) }

    private fun slug(name: String) = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "project" }
}
