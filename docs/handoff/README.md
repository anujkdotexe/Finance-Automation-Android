# Handoff channel

This folder is the message channel between the build agent and the reviewer.

- `TO_AGENT.md`: current instructions and corrections from the reviewer. The build agent reads it at the start of every round.
- `FROM_AGENT.md`: the build agent's latest report, in the format from section 0 of `ANDROID_V8_PHASE_GUIDE.md`.
- `STATUS.md`: one-line status of the build agent.
- `history/`: archived earlier messages and reports.

Both sides use the MCP server in `tools/handoff-mcp/` (tools: `read_inbox`, `post_report`, `send_to_agent`, `read_report`, `wait_for_instructions`, `wait_for_report`). If the server is unavailable, read and write these files directly. Setup steps are in `tools/handoff-mcp/README.md`.

## Round protocol

A round is one instruction message and the report that answers it.

1. The reviewer sends exactly one instruction message per round, and only after the previous report has been fully verified against git and the files. The reviewer does not write to `TO_AGENT.md` while a round is open, and does not send partial or follow-up notes.
2. The build agent reads the message at the start of the round, works only on that message, and posts one report at the end. It then waits.
3. Nothing in the message refers to work from a different round. If an instruction changes, the reviewer waits for the report and sends the change in the next round.

## Git workflow

Git is the only safety net. No backup copies, no copies outside the repository.

1. Each phase works on its own branch cut from `main`, named `feature/phase-N-short-name`.
2. When the build agent finishes its first draft of a phase, it commits it on that branch before anything else happens, with a detailed message, and reports the commit hash.
3. The reviewer reads the work from git (`git log`, `git diff main...<branch>`, `git show`) and from the files, then sends one message with the fixes.
4. The build agent applies the fixes and commits them as separate commits on the same branch, then reports the hashes.
5. Steps 3 and 4 repeat until the reviewer judges the phase complete.
6. The build agent pushes the branch and opens a pull request into `main`. The reviewer reviews the pull request, and then the build agent squash-merges it and deletes the branch.
7. Uncommitted work is never reverted, reset, cleaned or deleted. Before any git state change, commit first.
8. The reviewer never pushes and never commits unless the owner has said so; the owner decides when anything leaves the machine.
