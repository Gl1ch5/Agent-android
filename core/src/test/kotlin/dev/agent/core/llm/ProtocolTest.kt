package dev.agent.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolTest {
    @Test
    fun parsesTextAndToolUseAndSkipsUnknownBlocks() {
        val raw = """
            {"content":[
              {"type":"thinking","thinking":"hmm","signature":"abc"},
              {"type":"text","text":"Let me look."},
              {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.kt"}}
            ],"stop_reason":"tool_use"}
        """.trimIndent()
        val response = parseMessagesResponse(raw)

        assertEquals("tool_use", response.stopReason)
        assertEquals(2, response.blocks.size)
        assertIs<ContentBlock.Text>(response.blocks[0])
        val call = assertIs<ContentBlock.ToolUse>(response.blocks[1])
        assertEquals("read_file", call.name)
        assertEquals("a.kt", (call.input["path"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun encodesToolResultWithSnakeCaseFields() {
        val json = AgentJson.encodeToString(
            ContentBlock.serializer(),
            ContentBlock.ToolResult("toolu_1", "ok", isError = true),
        )
        assertEquals(
            """{"type":"tool_result","tool_use_id":"toolu_1","content":"ok","is_error":true}""",
            json,
        )
    }
}
