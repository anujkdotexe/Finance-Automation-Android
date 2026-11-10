# Handoff MCP server

A small MCP server with no dependencies. It needs Node 18 or newer. It connects the build agent (in Antigravity) and the reviewer (Claude Code) through the files in `docs/handoff/`.

## Tools

| Tool | Used by | Effect |
| --- | --- | --- |
| `read_inbox` | build agent | returns `TO_AGENT.md` |
| `wait_for_instructions` | build agent | waits up to 30 seconds for a new `TO_AGENT.md`, else returns `NO_NEW_MESSAGE` |
| `wait_for_report` | reviewer | waits up to 30 seconds for a new `FROM_AGENT.md`, else returns `NO_NEW_REPORT` |
| `post_report` | build agent | writes `FROM_AGENT.md`, optionally `STATUS.md` |
| `send_to_agent` | reviewer | writes `TO_AGENT.md`, archives the previous one |
| `read_report` | reviewer | returns `FROM_AGENT.md` and `STATUS.md` |

## Setup in Antigravity

1. Open the agent panel, choose the menu, then MCP Servers, then Manage MCP Servers, then View raw config.
2. Add this entry. Replace `<PROJECT_ROOT>` with the absolute path of this repository on your machine:

```json
{
  "mcpServers": {
    "finance-handoff": {
      "command": "node",
      "args": ["<PROJECT_ROOT>/tools/handoff-mcp/server.mjs"]
    }
  }
}
```

3. Save and press Refresh. The four tools should appear under `finance-handoff`.
4. Add this rule to the agent's workspace rules: "At the start of every task call `read_inbox` from finance-handoff. At the end of every task call `post_report` with the report."

## Setup in Claude Code (reviewer)

Add the same entry to a `.mcp.json` file in the repository root, or run `claude mcp add finance-handoff -- node tools/handoff-mcp/server.mjs` from the repository root.

## Notes

- Set the `HANDOFF_DIR` environment variable to use a different folder.
- Messages are limited to 200 KB.
- The server only reads and writes inside the handoff folder and never touches the network.
