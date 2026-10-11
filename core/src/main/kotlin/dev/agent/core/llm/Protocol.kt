package dev.agent.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared JSON settings for talking to the Messages API and for persisting sessions. */
val AgentJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** One turn in the conversation. [role] is either "user" or "assistant". */
@Serializable
data class ApiMessage(val role: String, val content: List<ContentBlock>)

@Serializable
sealed interface ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : ContentBlock
}

/** Tool definition sent to the model. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
internal data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ApiMessage>,
    val tools: List<ToolSpec>,
)

/**
 * Response content is decoded lazily as raw objects so that block types we do not
 * model yet (for example "thinking") are skipped instead of failing the whole turn.
 */
@Serializable
internal data class MessagesResponseDto(
    val content: List<JsonObject>,
    @SerialName("stop_reason") val stopReason: String? = null,
)

internal fun parseMessagesResponse(raw: String): LlmResponse {
    val dto = AgentJson.decodeFromString(MessagesResponseDto.serializer(), raw)
    val blocks = dto.content.mapNotNull { obj ->
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> ContentBlock.Text(obj.string("text"))
            "tool_use" -> ContentBlock.ToolUse(
                id = obj.string("id"),
                name = obj.string("name"),
                input = obj["input"]?.jsonObject ?: JsonObject(emptyMap()),
            )
            else -> null
        }
    }
    return LlmResponse(blocks, dto.stopReason)
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing field '$key' in response block")
