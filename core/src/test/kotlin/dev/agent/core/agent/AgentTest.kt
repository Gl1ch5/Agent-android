package dev.agent.core.agent

import dev.agent.core.llm.ApiMessage
import dev.agent.core.llm.ContentBlock
import dev.agent.core.llm.LlmClient
import dev.agent.core.llm.LlmResponse
import dev.agent.core.llm.ToolSpec
import dev.agent.core.tools.ReadFileTool
import dev.agent.core.tools.Workspace
import dev.agent.core.tools.WriteFileTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Returns scripted responses in order and records what the agent sent. */
private class ScriptedLlm(private val script: List<LlmResponse>) : LlmClient {
    val calls = mutableListOf<List<ApiMessage>>()
    override suspend fun complete(system: String, messages: List<ApiMessage>, tools: List<ToolSpec>): LlmResponse {
        calls += messages.toList()
        return script[calls.size - 1]
    }
}

class AgentTest {
    private fun readArgs(path: String): JsonObject = buildJsonObject { put("path", JsonPrimitive(path)) }

    @Test
    fun runsToolCallThenReturnsFinalText() = kotlinx.coroutines.test.runTest {
        val ws = Workspace(createTempDir("agent-test"))
        File(ws.root, "notes.txt").writeText("hello agent")
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(listOf(ContentBlock.ToolUse("t1", "read_file", readArgs("notes.txt"))), "tool_use"),
                LlmResponse(listOf(ContentBlock.Text("File says hello.")), "end_turn"),
            ),
        )
        val events = mutableListOf<AgentEvent>()
        val agent = Agent(llm, listOf(ReadFileTool()), ws, { _, _ -> true }, { "sys" }, events::add)

        agent.send("what is in notes.txt?")

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.output.contains("hello agent"))
        assertTrue(events.last() is AgentEvent.Done)
        // user -> assistant(tool_use) -> user(tool_result) -> assistant(text)
        assertEquals(4, agent.messages.size)
        val toolResult = agent.messages[2].content.single() as ContentBlock.ToolResult
        assertEquals("t1", toolResult.toolUseId)
    }

    @Test
    fun deniedToolCallIsNotExecuted() = kotlinx.coroutines.test.runTest {
        val ws = Workspace(createTempDir("agent-deny"))
        val llm = ScriptedLlm(
            listOf(
                LlmResponse(
                    listOf(
                        ContentBlock.ToolUse(
                            "w1", "write_file",
                            buildJsonObject { put("path", JsonPrimitive("x.txt")); put("content", JsonPrimitive("hi")) },
                        ),
                    ),
                    "tool_use",
                ),
                LlmResponse(listOf(ContentBlock.Text("Okay, not writing.")), "end_turn"),
            ),
        )
        val agent = Agent(llm, listOf(WriteFileTool()), ws, { _, _ -> false }, { "sys" })

        agent.send("write x")

        assertTrue(!File(ws.root, "x.txt").exists())
        val result = agent.messages[2].content.single() as ContentBlock.ToolResult
        assertTrue(result.isError)
    }

    @Test
    fun stopsAtStepLimit() = kotlinx.coroutines.test.runTest {
        val ws = Workspace(createTempDir("agent-limit"))
        val loopingCall = LlmResponse(listOf(ContentBlock.ToolUse("t", "missing", JsonObject(emptyMap()))), "tool_use")
        val llm = object : LlmClient {
            override suspend fun complete(system: String, messages: List<ApiMessage>, tools: List<ToolSpec>) = loopingCall
        }
        val events = mutableListOf<AgentEvent>()
        Agent(llm, emptyList(), ws, { _, _ -> true }, { "sys" }, events::add, maxSteps = 3).send("go")

        assertTrue(events.last() is AgentEvent.Error)
    }
}
