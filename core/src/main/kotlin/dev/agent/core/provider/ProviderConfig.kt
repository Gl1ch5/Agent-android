package dev.agent.core.provider

import dev.agent.core.llm.AnthropicClient
import dev.agent.core.llm.LlmClient
import dev.agent.core.llm.OpenAiClient
import kotlinx.serialization.Serializable

/** Wire protocol a provider speaks. */
@Serializable
enum class ProviderKind { ANTHROPIC, OPENAI_COMPATIBLE }

/**
 * A model endpoint the user configured. Several can exist at once; the chat picks one
 * provider and one model. [models] is filled by "Fetch models" or typed in by hand.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
)

/** Builds the client for a provider and model. */
fun ProviderConfig.createClient(model: String = selectedModel): LlmClient = when (kind) {
    ProviderKind.ANTHROPIC -> AnthropicClient(apiKey = apiKey, model = model, endpoint = baseUrl.ifBlank { DEFAULT_ANTHROPIC_URL })
    ProviderKind.OPENAI_COMPATIBLE -> OpenAiClient(baseUrl = baseUrl, apiKey = apiKey, model = model)
}

const val DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"

/** Starting point for a new user: Anthropic with the default model. */
fun defaultProviders(): List<ProviderConfig> = listOf(
    ProviderConfig(
        id = "anthropic",
        name = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = DEFAULT_ANTHROPIC_URL,
        models = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"),
        selectedModel = "claude-sonnet-5-5",
    ),
)
