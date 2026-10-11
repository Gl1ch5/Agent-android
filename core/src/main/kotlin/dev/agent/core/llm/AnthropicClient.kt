package dev.agent.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Minimal Anthropic Messages API client with tool-use support. */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val maxTokens: Int = 8192,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build(),
) : LlmClient {

    override suspend fun complete(
        system: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val payload = AgentJson.encodeToString(
            MessagesRequest.serializer(),
            MessagesRequest(model, maxTokens, system, messages, tools),
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = http.newCall(request)
        // Cancelling the coroutine must also abort the blocking HTTP call.
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw LlmException(response.code, body)
                parseMessagesResponse(body)
            }
        } finally {
            handle?.dispose()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
