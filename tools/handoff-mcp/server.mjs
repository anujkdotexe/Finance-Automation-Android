// Handoff MCP server: a tiny message channel between the build agent and the reviewer.
// Zero dependencies. Speaks MCP over stdio (newline-delimited JSON-RPC 2.0).
// Messages are plain files in docs/handoff/ so they stay readable and reviewable in git.

import { promises as fs } from "node:fs";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const MAX_TEXT_BYTES = 200_000;

const here = path.dirname(fileURLToPath(import.meta.url));

// Optional named channel for a second worker (a parallel Claude Code session).
// Without HANDOFF_CHANNEL this server behaves exactly as version 1.0.0, so the build agent's
// channel is unchanged. A channel always needs its own HANDOFF_DIR so it never shares files.
const CHANNEL_PATTERN = /^[a-z0-9][a-z0-9-]{0,23}$/;
const channel = process.env.HANDOFF_CHANNEL ? process.env.HANDOFF_CHANNEL.trim() : "";
if (channel && !CHANNEL_PATTERN.test(channel)) {
  process.stderr.write(`Invalid HANDOFF_CHANNEL "${channel}": use lower case letters, digits and hyphens (1 to 24 characters).\n`);
  process.exit(1);
}
if (channel && !process.env.HANDOFF_DIR) {
  process.stderr.write("HANDOFF_CHANNEL requires HANDOFF_DIR so a channel never shares the build agent's folder.\n");
  process.exit(1);
}

const SERVER_NAME = channel ? `finance-handoff-${channel}` : "finance-handoff";
const SERVER_VERSION = channel ? "1.1.0" : "1.0.0";

async function loadChannelInstructions() {
  for (const name of [`${channel}.md`, "worker.md"]) {
    try {
      return await fs.readFile(path.join(here, "channels", name), "utf8");
    } catch (error) {
      if (!error || error.code !== "ENOENT") throw error;
    }
  }
  return "";
}

// Standing rules for the worker session, sent to its client as MCP server instructions.
const CHANNEL_INSTRUCTIONS = channel ? await loadChannelInstructions() : "";

const handoffDir = process.env.HANDOFF_DIR
  ? path.resolve(process.env.HANDOFF_DIR)
  : path.resolve(here, "..", "..", "docs", "handoff");
const historyDir = path.join(handoffDir, "history");

const FILES = {
  toAgent: path.join(handoffDir, "TO_AGENT.md"),
  fromAgent: path.join(handoffDir, "FROM_AGENT.md"),
  status: path.join(handoffDir, "STATUS.md"),
};

const BASE_TOOLS = [
  {
    name: "read_inbox",
    description:
      "For the build agent. Returns the current instructions and corrections from the reviewer (TO_AGENT.md). Call this at the start of every phase.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "wait_for_instructions",
    description:
      "For the build agent. After posting a report, call this repeatedly to wait for the reviewer's next instructions. Returns the new TO_AGENT.md when it changes, or 'NO_NEW_MESSAGE' after up to 30 seconds. On NO_NEW_MESSAGE call it again; stop only when the message says STOP.",
    inputSchema: {
      type: "object",
      properties: { timeoutSeconds: { type: "number", description: "Wait up to this many seconds, 1 to 30." } },
      additionalProperties: false,
    },
  },
  {
    name: "wait_for_report",
    description:
      "For the reviewer. Waits up to 30 seconds for a new report from the build agent. Returns it when FROM_AGENT.md changes, or 'NO_NEW_REPORT' on timeout.",
    inputSchema: {
      type: "object",
      properties: { timeoutSeconds: { type: "number", description: "Wait up to this many seconds, 1 to 30." } },
      additionalProperties: false,
    },
  },
  {
    name: "post_report",
    description:
      "For the build agent. Overwrites FROM_AGENT.md with the report for the reviewer. Use the report format from section 0 of docs/ANDROID_V8_PHASE_GUIDE.md. Optionally sets a one-line status.",
    inputSchema: {
      type: "object",
      properties: {
        report: { type: "string", description: "Full report text in markdown." },
        status: { type: "string", description: "One-line status, for example 'Phase 2.4 in progress'." },
      },
      required: ["report"],
      additionalProperties: false,
    },
  },
  {
    name: "send_to_agent",
    description:
      "For the reviewer. Overwrites TO_AGENT.md with new instructions or corrections for the build agent. The previous message is archived in history/.",
    inputSchema: {
      type: "object",
      properties: { message: { type: "string", description: "Instructions in markdown." } },
      required: ["message"],
      additionalProperties: false,
    },
  },
  {
    name: "read_report",
    description:
      "For the reviewer. Returns the latest report from the build agent (FROM_AGENT.md) and the current status line.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
];

// In a channel the descriptions address the worker session, never the Antigravity build agent.
const TOOLS = channel
  ? BASE_TOOLS.map((tool) => ({
      ...tool,
      description: `[Channel ${channel}, for the parallel Claude Code worker session only] ${tool.description.replaceAll("the build agent", "the worker session")}`,
    }))
  : BASE_TOOLS;

function log(message) {
  process.stderr.write(`[${SERVER_NAME}] ${message}\n`);
}

function stamp() {
  return new Date().toISOString();
}

async function readOrEmpty(file) {
  try {
    return await fs.readFile(file, "utf8");
  } catch (error) {
    if (error && error.code === "ENOENT") return "";
    throw error;
  }
}

async function atomicWrite(file, content) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  const temp = `${file}.${process.pid}.tmp`;
  await fs.writeFile(temp, content, "utf8");
  await fs.rename(temp, file);
}

async function archive(label, previous) {
  if (!previous.trim()) return;
  const name = `${stamp().replace(/[:.]/g, "-")}_${label}.md`;
  await atomicWrite(path.join(historyDir, name), previous);
}

function requireText(value, field) {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${field} must be a non-empty string`);
  }
  if (Buffer.byteLength(value, "utf8") > MAX_TEXT_BYTES) {
    throw new Error(`${field} is larger than ${MAX_TEXT_BYTES} bytes`);
  }
  return value;
}

const MAX_WAIT_SECONDS = 30;
const POLL_MS = 1000;

// Last content each side has been shown, so a wait only returns something new.
const seen = { toAgent: "", fromAgent: "" };

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForChange(key, noChangeText, requestedSeconds) {
  const requested = Number.isFinite(requestedSeconds) ? requestedSeconds : MAX_WAIT_SECONDS;
  const seconds = Math.min(Math.max(requested, 1), MAX_WAIT_SECONDS);
  const deadline = Date.now() + seconds * 1000;
  while (true) {
    const current = await readOrEmpty(FILES[key]);
    if (current.trim() && current !== seen[key]) {
      seen[key] = current;
      return current;
    }
    if (Date.now() >= deadline) return noChangeText;
    await sleep(POLL_MS);
  }
}

async function callTool(name, args) {
  const input = args && typeof args === "object" ? args : {};
  switch (name) {
    case "read_inbox": {
      const text = await readOrEmpty(FILES.toAgent);
      seen.toAgent = text;
      return text.trim() ? text : "No instructions yet.";
    }
    case "wait_for_instructions":
      return waitForChange("toAgent", "NO_NEW_MESSAGE", input.timeoutSeconds);
    case "wait_for_report":
      return waitForChange("fromAgent", "NO_NEW_REPORT", input.timeoutSeconds);
    case "post_report": {
      const report = requireText(input.report, "report");
      await archive("report", await readOrEmpty(FILES.fromAgent));
      await atomicWrite(FILES.fromAgent, `<!-- posted ${stamp()} -->\n${report}\n`);
      if (typeof input.status === "string" && input.status.trim()) {
        await atomicWrite(FILES.status, `${input.status.trim()}\n\nUpdated ${stamp()}\n`);
      }
      return "Report saved to FROM_AGENT.md.";
    }
    case "send_to_agent": {
      const message = requireText(input.message, "message");
      await archive("instructions", await readOrEmpty(FILES.toAgent));
      await atomicWrite(FILES.toAgent, `<!-- sent ${stamp()} -->\n${message}\n`);
      return "Message saved to TO_AGENT.md.";
    }
    case "read_report": {
      const report = await readOrEmpty(FILES.fromAgent);
      const status = await readOrEmpty(FILES.status);
      return `Status:\n${status.trim() || "none"}\n\nReport:\n${report.trim() || "No report yet."}`;
    }
    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}

function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

async function handle(request) {
  const { id, method, params } = request;
  const isNotification = id === undefined || id === null;

  try {
    switch (method) {
      case "initialize":
        send({
          jsonrpc: "2.0",
          id,
          result: {
            protocolVersion: params && params.protocolVersion ? params.protocolVersion : "2024-11-05",
            capabilities: { tools: {} },
            serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
            ...(CHANNEL_INSTRUCTIONS ? { instructions: CHANNEL_INSTRUCTIONS } : {}),
          },
        });
        return;
      case "ping":
        send({ jsonrpc: "2.0", id, result: {} });
        return;
      case "tools/list":
        send({ jsonrpc: "2.0", id, result: { tools: TOOLS } });
        return;
      case "tools/call": {
        try {
          const text = await callTool(params && params.name, params && params.arguments);
          send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text }] } });
        } catch (error) {
          const text = error instanceof Error ? error.message : String(error);
          send({ jsonrpc: "2.0", id, result: { isError: true, content: [{ type: "text", text }] } });
        }
        return;
      }
      default:
        if (!isNotification) {
          send({ jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
        }
    }
  } catch (error) {
    log(`handler failure: ${error instanceof Error ? error.message : String(error)}`);
    if (!isNotification) {
      send({ jsonrpc: "2.0", id, error: { code: -32603, message: "Internal error" } });
    }
  }
}

// Requests are handled one at a time, in order, so a read never overtakes an earlier write.
let queue = Promise.resolve();

const input = readline.createInterface({ input: process.stdin });
input.on("line", (rawLine) => {
  const line = rawLine.replace(/^﻿/, "");
  if (!line.trim()) return;
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
    return;
  }
  queue = queue.then(() => handle(request));
});
input.on("close", () => {
  void queue.then(() => process.exit(0));
});
log(`ready, handoff folder: ${path.relative(process.cwd(), handoffDir) || "."}`);
