# Advisor and self-improvement

## Goal

The project should be able to improve itself. A separate **advisor** role plans the work,
reviews results, and decides what to change next. The agent is the coder that carries out
those plans on the phone or in CI.

## Stages

1. **Memory (done in this version).** The agent can record lessons with `append_memory`.
   They go to `.agent/memory.md` in the workspace and are loaded into the system prompt of
   later sessions. Every write needs approval, so the user reviews what gets remembered.
2. **Project instructions (done).** `AGENTS.md` in the workspace is loaded the same way and
   describes conventions, build commands and rules for the agent.
3. **Self-change proposals (next).** The agent works on a copy of this repository inside the
   workspace, runs `./gradlew :core:test`, and opens a pull request with its change. A human
   merges it. The nightly workflow then ships the result.
4. **Advisor loop (later).** A scheduled routine reads open issues, CI results and the
   nightly changelog, writes a short plan into `docs/plans/`, and opens issues for the agent
   to pick up.

## Guardrails

- The agent never merges its own pull requests and never pushes to the default branch.
- Changes to the approval rules, the workspace sandbox and the release workflows always need
  human review.
- Every self-change must keep `:core:test` green. Tests for the sandbox and the approval
  gate are the safety net for anything the agent edits.
- Secrets (API keys, signing keys) never go into memory, prompts or the repository.

## Open questions

- Termux integration vs. an in-app toolchain for building Android projects on the device.
  Termux gives git, JDK and Gradle, but needs the `RUN_COMMAND` permission and a user setup
  step. An in-app shell is simpler but has fewer tools.
- Whether the advisor role runs on the device, in CI, or in a cloud session.
