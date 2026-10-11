package dev.agent.app

import kotlinx.serialization.Serializable

/** A workspace the agent works in. [path] is a real filesystem directory. */
@Serializable
data class Project(val id: String, val name: String, val path: String)
