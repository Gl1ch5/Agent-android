package dev.agent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for any OpenAI-compatible Chat Completions API (OpenAI, OpenRouter, Ollama,
 * LM Studio, vLLM, ...). The agent keeps its Anthropic-style history internally; this
 * class converts to and from the OpenAI wire format at the edges.
 */
class OpenAiClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 8192,
    private val http: OkHttpClient = defaultHttp(),
) : LlmClient {

    override suspend fun complete(
        system: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val payload = buildRequest(system, messages, tools)
        val request = Request.Builder()
            .url(chatUrl(baseUrl))
            .applyAuth(apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = execute(request)
        parseChatResponse(AgentJson.parseToJsonElement(body).jsonObject)
    }

    internal fun buildRequest(system: String, messages: List<ApiMessage>, tools: List<ToolSpec>): JsonObject =
        buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                messages.forEach { message -> toOpenAiMessages(message).forEach { add(it) } }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.inputSchema)
                            }
                        })
                    }
                }
            }
        }

    private fun toOpenAiMessages(message: ApiMessage): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        if (message.role == "assistant") {
            val text = message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
            val calls = message.content.filterIsInstance<ContentBlock.ToolUse>()
            out += buildJsonObject {
                put("role", "assistant")
                put("content", text.ifEmpty { null as String? })
                if (calls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        calls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.input.toString())
                                }
                            })
                        }
                    }
                }
            }
        } else {
            // Tool results become separate "tool" messages; any plain text stays a user message.
            message.content.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                out += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", result.toolUseId)
                    put("content", result.content)
                }
            }
            val text = message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
            if (text.isNotEmpty()) out += buildJsonObject {
                put("role", "user")
                put("content", text)
            }
        }
        return out
    }

    private suspend fun execute(request: Request): String {
        val call = http.newCall(request)
        // Cancelling the coroutine must also abort the blocking HTTP call.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmException(response.code, body)
                return body
            }
        } finally {
            handle?.dispose()
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .build()

        /** Accepts a base like "https://host/v1", "https://host" or a full chat URL. */
        fun chatUrl(baseUrl: String): String = endpoint(baseUrl, "chat/completions")

        fun modelsUrl(baseUrl: String): String = endpoint(baseUrl, "models")

        private fun endpoint(baseUrl: String, path: String): String {
            val base = baseUrl.trim().trimEnd('/')
            require(base.startsWith("http://") || base.startsWith("https://")) { "Base URL must start with http:// or https://" }
            return if (base.endsWith("/$path")) base else "$base/$path"
        }

        /** Lists model ids from GET {base}/models. Used by the "Fetch models" button. */
        suspend fun listModels(baseUrl: String, apiKey: String, http: OkHttpClient = defaultHttp()): List<String> =
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(modelsUrl(baseUrl))
                    .applyAuth(apiKey)
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw LlmException(response.code, body)
                    val data = AgentJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                    data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.sorted()
                }
            }

        internal fun parseChatResponse(root: JsonObject): LlmResponse {
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("Response has no choices")
            val message = choice["message"]?.jsonObject ?: error("Response choice has no message")
            val blocks = mutableListOf<ContentBlock>()
            (message["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                blocks += ContentBlock.Text(it)
            }
            message["tool_calls"]?.jsonArray.orEmpty().forEach { element ->
                val call = element.jsonObject
                val function = call["function"]!!.jsonObject
                val args = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                blocks += ContentBlock.ToolUse(
                    id = call["id"]!!.jsonPrimitive.content,
                    name = function["name"]!!.jsonPrimitive.content,
                    input = if (args.isBlank()) JsonObject(emptyMap()) else AgentJson.parseToJsonElement(args).jsonObject,
                )
            }
            val stop = when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
                "tool_calls" -> "tool_use"
                "length" -> "max_tokens"
                else -> "end_turn"
            }
            return LlmResponse(blocks, stop)
        }

        private fun okhttp3.Request.Builder.applyAuth(apiKey: String) =
            if (apiKey.isBlank()) this else header("Authorization", "Bearer $apiKey")
    }
}
