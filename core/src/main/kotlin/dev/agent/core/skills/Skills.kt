package dev.agent.core.skills

import dev.agent.core.tools.Tool
import dev.agent.core.tools.ToolOutput
import dev.agent.core.tools.Workspace
import dev.agent.core.tools.objectSchema
import dev.agent.core.tools.property
import dev.agent.core.tools.requireString
import kotlinx.serialization.json.JsonObject
import java.io.File

/** A skill is a folder with a SKILL.md file: YAML-ish frontmatter plus instructions. */
data class Skill(val name: String, val description: String, val body: String, val file: File)

object SkillLoader {
    /** Skill folders searched in order. Later folders override earlier ones with the same name. */
    val SEARCH_DIRS = listOf(".agent/skills", ".claude/skills")
    private const val MAX_BODY_CHARS = 30_000

    fun load(workspace: Workspace): List<Skill> {
        val byName = linkedMapOf<String, Skill>()
        SEARCH_DIRS.forEach { dir ->
            File(workspace.root, dir).listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .mapNotNull { parse(File(it, "SKILL.md")) }
                .forEach { byName[it.name] = it }
        }
        return byName.values.toList()
    }

    fun parse(file: File): Skill? {
        if (!file.isFile) return null
        val text = file.readText()
        val match = Regex("""\A---\s*\n(.*?)\n---\s*\n?(.*)\z""", RegexOption.DOT_MATCHES_ALL).find(text)
        val front = match?.groupValues?.get(1).orEmpty()
        val body = (match?.groupValues?.get(2) ?: text).take(MAX_BODY_CHARS)
        val fields = front.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: file.parentFile?.name ?: return null
        return Skill(name, fields["description"].orEmpty(), body, file)
    }

    /** Short index for the system prompt: name and description only. Bodies load on demand. */
    fun index(skills: List<Skill>): String = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
}

/** Lets the model read a skill's full instructions when its description matches the task. */
class LoadSkillTool(private val skills: () -> List<Skill>) : Tool {
    override val name = "load_skill"
    override val description = "Load the full instructions of a skill listed in the system prompt."
    override val inputSchema = objectSchema(
        "name" to property("string", "Skill name exactly as listed."),
        required = listOf("name"),
    )

    override suspend fun execute(input: JsonObject, workspace: Workspace): ToolOutput {
        val wanted = input.requireString("name")
        val skill = skills().firstOrNull { it.name == wanted }
            ?: return ToolOutput("No skill named '$wanted'", isError = true)
        return ToolOutput(skill.body)
    }
}
