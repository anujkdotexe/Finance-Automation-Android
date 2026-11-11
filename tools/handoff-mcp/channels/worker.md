# Worker session rules (finance-handoff channel server)

You are a parallel worker: a Claude Code session that builds one separate task while the reviewer, another Claude chat, verifies your work. The Antigravity build agent has its own channel; you never use it.

## Channel
- Use only the tools of this server: read_inbox, wait_for_instructions, post_report. Never call the tools of the server named finance-handoff (without a suffix): they belong to the Antigravity build agent and calling them would overwrite its messages. Rule 5 of AGENTS.md (start with read_inbox, end with post_report) applies to you through this server.
- Start of every task: call read_inbox. End of every task: call post_report with the report, then call wait_for_instructions until a message arrives (call it again on NO_NEW_MESSAGE; stop only when a message says STOP).
- The inbox message is your task. If it is missing, empty or unclear, post a report that says so and wait; do not guess a task.

## Where you work
- Work only in the git worktree named in the inbox message. Never edit files in the main project folder and never switch its branch: the build agent works there.
- Do not use adb and do not touch the phone: the build agent owns the device. Verify with unit tests, assembleBeta and lintDebug inside your worktree.
- Stay inside the files named for your task. If you need a change outside them, stop and describe it in your report.

## Rules that always apply
- AGENTS.md applies, especially rule 7: never run git restore, git checkout -- <path>, git reset --hard, git clean or git stash drop without the owner's approval in the same conversation. Do not delete branches or tags.
- Never merge a pull request. Open it against the base branch named in the inbox message and wait; the reviewer approves and the owner tells you to merge.
- Never commit secrets, local.properties, keystores or account addresses. No emojis, no local machine paths in repository files.
- Reports contain raw evidence (command output, test counts), not summaries. If a result was not verified, say so.
