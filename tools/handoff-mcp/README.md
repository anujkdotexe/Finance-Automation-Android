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

## Second channel for a parallel Claude Code worker

The build agent in Antigravity keeps the default server above and is not affected by anything in this section. A second Claude Code session that works in parallel gets its own channel: a second server entry with its own folder, its own name and its own standing rules.

- The channel is selected with two environment variables: `HANDOFF_CHANNEL` (a name made of lower case letters, digits and hyphens) and `HANDOFF_DIR` (a folder of its own, which must not be the default `docs/handoff`). The server refuses to start if either is invalid or missing.
- A channel server reports itself as `finance-handoff-<name>`, prefixes its tool descriptions with the channel name and the words "parallel Claude Code worker session only", and sends the rules in `channels/worker.md` to its client as MCP server instructions. Add `channels/<name>.md` to give one channel different rules.
- The folder holds the same files as the default channel (`TO_AGENT.md`, `FROM_AGENT.md`, `STATUS.md`, `history/`). Put it outside the git tree so it never shows up as a change in a working copy.
- Register it only in the worker session, not in the Antigravity configuration, so the build agent never sees these tools. Example for Claude Code, run from the worker's own worktree (replace the two placeholders):

```text
claude mcp add finance-handoff-claude2 --scope local -e HANDOFF_CHANNEL=claude2 -e HANDOFF_DIR=<CHANNEL_FOLDER> -- node <PROJECT_ROOT>/tools/handoff-mcp/server.mjs
```

- The reviewer uses the same tools on the channel server (`send_to_agent`, `read_report`, `wait_for_report`) once it is registered in the reviewer's session, or edits the channel files directly.
- Check the server with `node tools/handoff-mcp/smoke-test.mjs`. It verifies that the default channel is unchanged and that channels do not share files.

## Notes

- Set the `HANDOFF_DIR` environment variable to use a different folder.
- Messages are limited to 200 KB.
- The server only reads and writes inside the handoff folder and never touches the network.
