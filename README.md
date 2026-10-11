# Agent-android

A coding agent for Android, in the spirit of Claude Code or Codex, that runs on the phone.
It talks to an LLM, reads and edits files in a workspace, runs shell commands, and asks
before every action that changes something.

## Layout

| Path | What it is |
| --- | --- |
| `core/` | Pure Kotlin/JVM engine: Anthropic client, tool-use loop, tools, workspace sandbox. No Android APIs, so it is tested on any JVM. |
| `app/` | Android app (Jetpack Compose): chat screen, approval dialog, settings. |
| `.github/workflows/ci.yml` | Runs engine tests, app unit tests and a debug build on every push and PR. |
| `.github/workflows/nightly.yml` | Daily build published as the rolling `nightly` pre-release. |
| `.github/workflows/tag-release.yml` | Manual release from a `vX.Y.Z` tag. |
| `docs/ADVISOR.md` | Plan for the project advisor and self-improvement loop. |

## Build

```bash
./gradlew :core:test                 # engine tests, no Android SDK needed
./gradlew :app:assembleDebug         # needs Android SDK (API 35)
```

## Using the app

1. Install the APK from the latest `nightly` release.
2. Open **Settings**, paste an Anthropic API key, and keep the default model or change it.
3. Ask for something, for example "list the files and summarize the project".
   The agent works in its workspace folder inside the app's private storage.
4. Tool calls that write files, edit files, run shell commands or record memory show an
   **Allow / Deny** dialog. Nothing risky runs without your yes.

## Tools available to the agent

| Tool | Approval | Purpose |
| --- | --- | --- |
| `list_dir` | no | List a directory in the workspace |
| `read_file` | no | Read a file with line numbers |
| `grep` | no | Regex search across files |
| `write_file` | yes | Create or overwrite a file |
| `edit_file` | yes | Exact string replacement, must be unique unless `replace_all` |
| `shell` | yes | Run `sh -c` in the workspace with a timeout |
| `append_memory` | yes | Record a durable project lesson in `.agent/memory.md` |

Paths are confined to the workspace, including through symlinks.

## Roadmap

- Get a workspace from git (clone a repo into the workspace).
- Streaming responses and cancel button.
- Session persistence and resume.
- Termux bridge for a real toolchain (git, JDK, Gradle) on the device.
- Advisor loop that proposes changes to this repo as PRs (see `docs/ADVISOR.md`).
- Signed release builds through repository secrets.
