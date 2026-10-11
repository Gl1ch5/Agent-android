package dev.agent.app

import android.content.Context
import dev.agent.core.llm.AgentJson
import dev.agent.core.provider.ProviderConfig
import dev.agent.core.provider.defaultProviders
import kotlinx.serialization.builtins.ListSerializer

/** User settings persisted in SharedPreferences: provider list and the active choice. */
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

    private companion object {
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE = "active_provider"
    }
}
