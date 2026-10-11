package dev.agent.app

import dev.agent.core.llm.AgentJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Git work for projects: clone a repository, commit a task's changes to a new branch and open
 * a pull request. Uses JGit for the repository and the GitHub REST API for the pull request.
 */
object GitHubOps {
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()
    private val remotePattern = Regex("""github\.com[/:]([^/]+)/([^/.]+?)(?:\.git)?/?$""")

    /** Clones [url] into [dir]. [token] is only needed for private repositories. */
    fun clone(url: String, dir: File, token: String?) {
        val command = Git.cloneRepository().setURI(url).setDirectory(dir)
        if (!token.isNullOrBlank()) command.setCredentialsProvider(credentials(token))
        command.call().close()
    }

    /** Commits every change in [dir] on a new branch, pushes it and opens a pull request. Returns its URL. */
    fun openPullRequest(dir: File, branch: String, title: String, body: String, token: String): String {
        Git.open(dir).use { git ->
            val remoteUrl = git.repository.config.getString("remote", "origin", "url")
                ?: throw IllegalStateException("This project has no GitHub remote.")
            val match = remotePattern.find(remoteUrl)
                ?: throw IllegalStateException("Only github.com remotes are supported: $remoteUrl")
            val (owner, repo) = match.destructured
            val base = git.repository.branch

            git.checkout().setCreateBranch(true).setName(branch).call()
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            if (git.status().call().isClean) throw IllegalStateException("No file changes to open a pull request for.")
            git.commit()
                .setMessage(title)
                .setAuthor("Agent", "agent@localhost")
                .call()
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .setCredentialsProvider(credentials(token))
                .call()

            return createPullRequest(owner, repo, head = branch, base = base, title = title, body = body, token = token)
        }
    }

    private fun createPullRequest(
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
        token: String,
    ): String {
        val payload = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/pulls")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody(json))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub refused the pull request (HTTP ${response.code}): $text")
            }
            return AgentJson.parseToJsonElement(text).jsonObject.getValue("html_url").jsonPrimitive.content
        }
    }

    private fun credentials(token: String) = UsernamePasswordCredentialsProvider("x-access-token", token)

    /** A branch name for a task: "agent/<slug>-<short id>". */
    fun branchFor(title: String, taskId: String): String {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "task" }
        return "agent/$slug-${taskId.take(6)}"
    }
}
