# Handoff channel

This folder is the message channel between the build agent and the reviewer.

- `TO_AGENT.md`: current instructions and corrections from the reviewer. The build agent reads it at the start of every phase.
- `FROM_AGENT.md`: the build agent's latest report, in the format from section 0 of `ANDROID_V8_PHASE_GUIDE.md`.
- `STATUS.md`: one-line status of the build agent.
- `history/`: archived earlier messages and reports.

Both sides use the MCP server in `tools/handoff-mcp/` (tools: `read_inbox`, `post_report`, `send_to_agent`, `read_report`). If the server is unavailable, read and write these files directly. Setup steps are in `tools/handoff-mcp/README.md`.
