package dev.agent.core.tools

/** The tool set available to a coding session, in the order the model sees it. */
fun defaultTools(skills: () -> List<dev.agent.core.skills.Skill> = { emptyList() }): List<Tool> = listOf(
    ListDirTool(),
    ReadFileTool(),
    GrepTool(),
    WriteFileTool(),
    EditFileTool(),
    ShellTool(),
    AppendMemoryTool(),
    dev.agent.core.skills.LoadSkillTool(skills),
)
