package dev.agent.core.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

private const val MAX_OUTPUT_CHARS = 20_000
private const val DEFAULT_TIMEOUT_SECONDS = 60
private const val MAX_TIMEOUT_SECONDS = 600

/**
 * Runs a command with `sh -c` inside the workspace. This is powerful, so it always
 * needs approval. Output is capped and the process is killed when it times out.
 */
class ShellTool(private val shell: String = "/system/bin/sh") : Tool {
    override val name = "shell"
    override val description =
        "Run a shell command in the workspace directory. Returns exit code, stdout and stderr. " +
            "Use for builds, tests, git and other CLI tools."
    override val inputSchema = objectSchema(
        "command" to property("string", "The command line to run."),
        "timeout_seconds" to property("integer", "Timeout in seconds. Defaults to $DEFAULT_TIMEOUT_SECONDS, max $MAX_TIMEOUT_SECONDS."),
        required = listOf("command"),
    )
    override val requiresApproval = true

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput = coroutineScope {
        val command = input.requireString("command")
        val timeout = (input.optionalInt("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val process = ProcessBuilder(shell, "-c", command)
            .directory(workspace.root)
            .redirectErrorStream(true)
            .start()

        // Read output concurrently so a chatty process cannot block on a full pipe.
        val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
        val finished = withContext(Dispatchers.IO) { process.waitFor(timeout.toLong(), TimeUnit.SECONDS) }
        if (!finished) {
            process.destroyForcibly()
            val partial = withTimeoutOrNull(1_000) { output.await() }.orEmpty()
            return@coroutineScope ToolOutput(
                "Timed out after ${timeout}s and was killed.\n${partial.takeLast(MAX_OUTPUT_CHARS)}",
                isError = true,
            )
        }
        val text = output.await()
        val trimmed = if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "\n... (output truncated)"
        } else text
        ToolOutput("exit code: ${process.exitValue()}\n$trimmed".trimEnd(), isError = process.exitValue() != 0)
    }
}
