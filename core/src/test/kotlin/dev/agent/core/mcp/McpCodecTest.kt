package dev.agent.core.mcp

import dev.agent.core.llm.AgentJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpCodecTest {
    private fun obj(text: String): JsonObject = AgentJson.parseToJsonElement(text).let { it as JsonObject }

    @Test
    fun `plain json reply is matched by id`() {
        val reply = McpCodec.findResponse("""{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""", 3, isStream = false)
        assertNotNull(reply)
        assertTrue(reply.containsKey("result"))
    }

    @Test
    fun `sse reply picks the data line with our id`() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress"}

            event: message
            data: {"jsonrpc":"2.0","id":7,"result":{"tools":[]}}

        """.trimIndent()
        val reply = McpCodec.findResponse(body, 7, isStream = true)
        assertNotNull(reply)
        assertEquals("7", (reply["id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `reply with another id is ignored`() {
        assertNull(McpCodec.findResponse("""{"id":2,"result":{}}""", 9, isStream = false))
    }

    @Test
    fun `text blocks are joined and isError is read`() {
        val result = obj("""{"content":[{"type":"text","text":"a"},{"type":"image","data":"x"},{"type":"text","text":"b"}],"isError":true}""")
        val output = McpCodec.toolOutput(result)
        assertEquals("a\nb", output.text)
        assertTrue(output.isError)
    }

    @Test
    fun `empty result says no output`() {
        val output = McpCodec.toolOutput(obj("""{"content":[]}"""))
        assertEquals("(no output)", output.text)
        assertEquals(false, output.isError)
    }
}
