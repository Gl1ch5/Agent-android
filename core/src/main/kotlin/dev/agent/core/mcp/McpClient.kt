package dev.agent.core.mcp

import dev.agent.core.llm.AgentJson
import dev.agent.core.tools.Tool
import dev.agent.core.tools.ToolOutput
import dev.agent.core.tools.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** An MCP server reached over HTTP (the streamable HTTP transport). [url] is its JSON-RPC endpoint. */
@Serializable
data class McpServerConfig(val id: String, val name: String, val url: String)

/** A tool a server advertises: its name, description and JSON-schema input. */
data class McpToolInfo(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * Minimal MCP client over HTTP JSON-RPC. Each call is a POST; the reply is plain JSON or an
 * SSE stream whose `data:` lines carry the JSON-RPC response with our id.
 */
class McpClient(
    private val server: McpServerConfig,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private var sessionId: String? = null
    private var nextId = 1

    suspend fun initialize() {
        request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "agent-android")
                put("version", "0.1.0")
            }
        })
        // Notifications have no id and no reply.
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
    }

    suspend fun listTools(): List<McpToolInfo> {
        val result = request("tools/list", null).jsonObject
        return result.getValue("tools").jsonArray.map { element ->
            val tool = element.jsonObject
            McpToolInfo(
                name = tool.getValue("name").jsonPrimitive.content,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = tool["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
            )
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): ToolOutput {
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject
        return McpCodec.toolOutput(result)
    }

    private suspend fun request(method: String, params: JsonObject?): JsonElement {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val reply = post(body, expectId = id)
        reply["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw IllegalStateException("MCP $method failed: $message")
        }
        return reply["result"] ?: throw IllegalStateException("MCP $method returned no result")
    }

    /** Posts one JSON-RPC message and returns the response object that matches [expectId]. */
    private suspend fun post(body: JsonObject, expectId: Int? = null): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(server.url)
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!response.isSuccessful) throw IllegalStateException("MCP server answered HTTP ${response.code}")
            if (expectId == null) return@withContext JsonObject(emptyMap())
            val text = response.body?.string().orEmpty()
            val isStream = response.header("Content-Type")?.startsWith("text/event-stream") == true
            McpCodec.findResponse(text, expectId, isStream)
                ?: throw IllegalStateException("MCP server sent no response for id $expectId")
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        val JSON = "application/json".toMediaType()
    }
}

/** Parsing helpers, kept free of I/O so they can be unit-tested. */
object McpCodec {
    /** Finds the JSON-RPC response with [id] in a plain body or an SSE body. */
    fun findResponse(body: String, id: Int, isStream: Boolean): JsonObject? {
        val candidates = if (isStream) {
            body.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
        } else {
            listOf(body)
        }
        return candidates.asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { AgentJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id.toString() }
    }

    /** Turns a tools/call result (a list of content blocks plus isError) into tool output. */
    fun toolOutput(result: JsonObject): ToolOutput {
        val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        return ToolOutput(text.ifEmpty { "(no output)" }, isError)
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}

/**
 * A tool served by an MCP server. External tools can do anything, so they always ask
 * for approval first.
 */
class McpTool(
    private val client: McpClient,
    private val remoteName: String,
    private val info: McpToolInfo,
    serverName: String,
) : Tool {
    override val name: String = "mcp_${sanitize(serverName)}_${sanitize(info.name)}"
    override val description: String = "[MCP ${serverName}] ${info.description}"
    override val inputSchema: JsonObject = info.inputSchema
    override val requiresApproval: Boolean = true

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput =
        try {
            client.callTool(info.name, input)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutput("MCP $remoteName failed: ${e.message}", isError = true)
        }

    private fun sanitize(text: String) = text.replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
}

/** Loads tools from every configured server. A server that fails is skipped, not fatal. */
object McpRegistry {
    suspend fun load(servers: List<McpServerConfig>): List<Tool> = servers.flatMap { server ->
        try {
            val client = McpClient(server)
            client.initialize()
            client.listTools().map { McpTool(client, it.name, it, server.name) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
}
