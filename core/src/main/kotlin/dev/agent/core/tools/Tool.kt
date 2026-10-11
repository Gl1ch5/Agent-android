package dev.agent.core.tools

import dev.agent.core.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Result of running a tool. [isError] is reported to the model so it can recover. */
data class ToolOutput(val text: String, val isError: Boolean = false)

/**
 * A capability the model can call. Tools that change state or run processes set
 * [requiresApproval], and the agent asks the user before running them.
 */
interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val requiresApproval: Boolean get() = false

    suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput

    fun spec() = ToolSpec(name, description, inputSchema)
}

/** Builds a JSON-schema object for tool input. */
fun objectSchema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (key, value) -> put(key, value) }
        }
        putJsonArray("required") {
            required.forEach { add(JsonPrimitive(it)) }
        }
    }

/** Builds a JSON-schema property description. */
fun property(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}

internal fun JsonObject.requireString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Missing required string argument '$key'")

internal fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optionalInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.optionalBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
