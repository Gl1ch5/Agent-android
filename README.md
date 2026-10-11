# Agent-android

A coding agent for Android, in the spirit of Codex or Claude Code, that runs on the phone.
You create projects (or open a folder on the phone), give the agent tasks, and read the
results and file diffs. The UI is a web page (HTML/CSS/JS) inside a WebView; the agent
engine is plain Kotlin.

## Layout

| Path | What it is |
| --- | --- |
| `core/` | Pure Kotlin/JVM engine: LLM clients (Anthropic, OpenAI-compatible), tool-use loop, tools, workspace sandbox, tasks, change tracking. No Android APIs. |
| `app/src/main/assets/web/` | The UI: `index.html`, `app.css`, `app.js`. Plain scripts, no build step. |
| `app/src/main/kotlin/dev/agent/app/` | Android shell: `MainActivity` (WebView, insets, folder picker, back), `AgentBridge` (the JS-to-Kotlin API), settings and project storage. |
| `signing/nightly-fallback.jks` | Committed fallback signing key, so each nightly installs over the last. See below. |
| `.github/workflows/ci.yml` | Engine tests, app unit tests and a debug build on every push and PR. |
| `.github/workflows/nightly.yml` | Build every 10 minutes, published as the rolling `nightly` pre-release. |
| `.github/workflows/tag-release.yml` | Manual release from a `vX.Y.Z` tag. |
| `docs/ADVISOR.md` | Plan for the project advisor and self-improvement loop. |

## Build

```bash
./gradlew :core:test                 # engine tests, no Android SDK needed
./gradlew :app:assembleDebug         # needs Android SDK (API 35)
```

## Using the app

1. Install the APK from the latest `nightly` release.
2. Open the providers panel (sliders icon, top right). Add a provider:
   - **OpenAI-compatible**: enter the base URL and key, then **Add models from /v1/models**.
     You can also type one model ID by hand.
   - **Anthropic**: enter the key and the model ID.
   Plain `http://` endpoints work, for example a local server on your network.
3. **New project** creates an empty project in the app's storage.
   **Open folder** picks a folder on the phone. Folders outside the app need
   all-files access, which the banner on the projects screen requests.
4. In a project, write a task and choose a mode:
   - **Ask** answers questions about the project. It can read and search only.
   - **Code** can also edit files and run shell commands.
   Each task keeps its own conversation. Follow-up messages continue it, even after the
   app restarts. Stop cancels a running task.
5. Open a task to see the transcript, the tool calls and the file **changes** with a
   line diff. Changes are detected from the file tree, so shell commands are included.

Projects and tasks are stored on disk: the project list in app settings, tasks in
`<project>/.agent/tasks/<id>/task.json`.

## Tools available to the agent

| Tool | Available in | Purpose |
| --- | --- | --- |
| `list_dir` | Ask, Code | List a directory in the project |
| `read_file` | Ask, Code | Read a file with line numbers |
| `grep` | Ask, Code | Regex search across files |
| `load_skill` | Ask, Code | Load a `SKILL.md` from `.agent/skills` or `.claude/skills` |
| `write_file` | Code | Create or overwrite a file |
| `edit_file` | Code | Exact string replacement, must be unique unless `replace_all` |
| `shell` | Code | Run `sh -c` in the project with a timeout |
| `append_memory` | Code | Record a durable lesson in `.agent/memory.md` |

Ask mode removes the write tools in the engine (`ToolPolicy`), so the restriction does not
depend on the model following instructions. Paths are confined to the project folder,
including through symlinks.

Tool calls currently run without a confirmation prompt; the approval gate is in the engine
and can be turned back on.

## Signing and upgrades

Every nightly is signed with the same key, so it installs over the previous one.
If the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` are set, that key is used instead. The committed fallback key is public, so
treat nightly builds as sideloaded personal builds, not as a trusted release channel.
Builds signed by an earlier runner-generated key must be uninstalled once.

## Not done yet

- **MCP** is not implemented. Skills load only through `load_skill`; slash commands, plugins and hooks are missing.
- **Background runs**: tasks run while the app process is alive. A foreground service with a notification is not implemented, and a run cut off by a restart is marked as interrupted.
- **Approvals** for Code tasks are off.
- **Anthropic model lists** are entered by hand. Only OpenAI-compatible endpoints can list models.
- **Folder access** on Android 10 and older needs the legacy storage permission, which is not requested.
- **Git** clone and pull requests are not implemented.
- **Icons** use the Material icon set inline in the UI; custom SVG icons are planned.
- The UI was tested in headless Chromium against a mock bridge. The Android side is compiled by CI but has not been run on a device.

## Roadmap

- MCP client over HTTP, with its tools namespaced `mcp__<server>__<tool>`.
- Foreground service for long Code tasks.
- Re-enable approvals for Code tasks, per task.
- Git: clone into a project, and open a PR for a task's changes.
- Advisor loop that proposes changes to this repo (see `docs/ADVISOR.md`).
- Signed release builds through repository secrets.
