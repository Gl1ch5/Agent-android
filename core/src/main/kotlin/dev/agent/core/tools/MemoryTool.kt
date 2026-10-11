package dev.agent.core.tools

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDate

/**
 * Lets the agent write durable lessons into `.agent/memory.md`. This is the first step of
 * self-improvement: lessons are loaded into the system prompt of later sessions.
 * Requires approval so the user can review what the agent decides to remember.
 */
class AppendMemoryTool : Tool {
    override val name = "append_memory"
    override val description =
        "Append a short, durable lesson about this project to .agent/memory.md. " +
            "Use for conventions, commands that work, and pitfalls. Do not store secrets."
    override val inputSchema = objectSchema(
        "lesson" to property("string", "One or two sentences describing the lesson."),
        required = listOf("lesson"),
    )
    override val requiresApproval = true

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val lesson = input.requireString("lesson").trim()
        if (lesson.isEmpty()) return ToolOutput("Lesson is empty", isError = true)
        val file = File(workspace.root, ".agent/memory.md")
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("# Agent memory\n\nLessons recorded by the agent.\n")
        file.appendText("\n- ${LocalDate.now()}: $lesson\n")
        return ToolOutput("Recorded lesson in .agent/memory.md")
    }
}
