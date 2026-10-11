package dev.agent.core.llm

/** Result of one model call: the content blocks it produced and why it stopped. */
data class LlmResponse(val blocks: List<ContentBlock>, val stopReason: String?)

/** Thrown when the provider answers with a non-2xx status. */
class LlmException(val statusCode: Int, val body: String) :
    RuntimeException("LLM request failed with HTTP $statusCode: ${body.take(500)}")

/** Abstraction over a model provider so the agent loop can be tested without network. */
interface LlmClient {
    suspend fun complete(
        system: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): LlmResponse
}
