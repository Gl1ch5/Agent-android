package dev.agent.app

import android.content.Context
import dev.agent.core.llm.AgentJson
import dev.agent.core.mcp.McpServerConfig
import dev.agent.core.provider.ProviderConfig
import dev.agent.core.provider.defaultProviders
import kotlinx.serialization.builtins.ListSerializer

/** User settings persisted in SharedPreferences: providers, active provider and projects. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("agent_settings", Context.MODE_PRIVATE)

    var providers: List<ProviderConfig>
        get() {
            val raw = prefs.getString(KEY_PROVIDERS, null) ?: return defaultProviders()
            return try {
                AgentJson.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
            } catch (e: Exception) {
                defaultProviders()
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_PROVIDERS, AgentJson.encodeToString(ListSerializer(ProviderConfig.serializer()), value))
                .apply()
        }

    var activeProviderId: String
        get() = prefs.getString(KEY_ACTIVE, null) ?: providers.first().id
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    var projects: List<Project>
        get() {
            val raw = prefs.getString(KEY_PROJECTS, null) ?: return emptyList()
            return try {
                AgentJson.decodeFromString(ListSerializer(Project.serializer()), raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_PROJECTS, AgentJson.encodeToString(ListSerializer(Project.serializer()), value))
                .apply()
        }

    var mcpServers: List<McpServerConfig>
        get() {
            val raw = prefs.getString(KEY_MCP, null) ?: return emptyList()
            return try {
                AgentJson.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            prefs.edit()
                .putString(KEY_MCP, AgentJson.encodeToString(ListSerializer(McpServerConfig.serializer()), value))
                .apply()
        }

    /** Personal access token for cloning private repos and opening pull requests. Empty when unset. */
    var githubToken: String
        get() = prefs.getString(KEY_GITHUB, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GITHUB, value).apply()

    private companion object {
        const val KEY_GITHUB = "github_token"
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE = "active_provider"
        const val KEY_PROJECTS = "projects"
        const val KEY_MCP = "mcp_servers"
    }
}
