// Smoke test for the handoff MCP server. Run: node tools/handoff-mcp/smoke-test.mjs
// Checks that the default channel is unchanged and that a named channel is isolated.

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, rmSync, existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const server = path.join(path.dirname(fileURLToPath(import.meta.url)), "server.mjs");

function startServer(env) {
  const child = spawn(process.execPath, [server], {
    env: { ...process.env, HANDOFF_DIR: "", HANDOFF_CHANNEL: "", ...env },
    stdio: ["pipe", "pipe", "ignore"],
  });
  const pending = new Map();
  readline.createInterface({ input: child.stdout }).on("line", (line) => {
    const message = JSON.parse(line);
    const resolve = pending.get(message.id);
    if (resolve) {
      pending.delete(message.id);
      resolve(message);
    }
  });
  let nextId = 1;
  const call = (method, params) =>
    new Promise((resolve) => {
      const id = nextId++;
      pending.set(id, resolve);
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
    });
  const tool = async (name, args = {}) => (await call("tools/call", { name, arguments: args })).result;
  const stop = () => child.kill();
  return { call, tool, stop };
}

const tmp = mkdtempSync(path.join(os.tmpdir(), "handoff-smoke-"));
const mainDir = path.join(tmp, "main");
const workerDir = path.join(tmp, "worker");
let failures = 0;

async function test(name, body) {
  try {
    await body();
    console.log(`PASS ${name}`);
  } catch (error) {
    failures++;
    console.log(`FAIL ${name}: ${error.message}`);
  }
}

await test("default channel is unchanged", async () => {
  const s = startServer({ HANDOFF_DIR: mainDir });
  try {
    const init = (await s.call("initialize", { protocolVersion: "2024-11-05" })).result;
    assert.equal(init.serverInfo.name, "finance-handoff");
    assert.equal(init.serverInfo.version, "1.0.0");
    assert.equal("instructions" in init, false);
    const tools = (await s.call("tools/list")).result.tools;
    assert.deepEqual(
      tools.map((t) => t.name),
      ["read_inbox", "wait_for_instructions", "wait_for_report", "post_report", "send_to_agent", "read_report"],
    );
    assert.ok(tools.every((t) => /^For the (build agent|reviewer)\./.test(t.description)));
  } finally {
    s.stop();
  }
});

await test("named channel has its own name, instructions and descriptions", async () => {
  const s = startServer({ HANDOFF_DIR: workerDir, HANDOFF_CHANNEL: "claude2" });
  try {
    const init = (await s.call("initialize", { protocolVersion: "2024-11-05" })).result;
    assert.equal(init.serverInfo.name, "finance-handoff-claude2");
    assert.equal(init.serverInfo.version, "1.1.0");
    assert.match(init.instructions, /Worker session rules/);
    assert.match(init.instructions, /Never call the tools of the server named finance-handoff/);
    const tools = (await s.call("tools/list")).result.tools;
    assert.equal(tools.length, 6);
    assert.ok(tools.every((t) => t.description.startsWith("[Channel claude2, for the parallel Claude Code worker session only]")));
    assert.ok(tools.every((t) => !t.description.includes("the build agent")));
  } finally {
    s.stop();
  }
});

await test("channels do not share files", async () => {
  const main = startServer({ HANDOFF_DIR: mainDir });
  const worker = startServer({ HANDOFF_DIR: workerDir, HANDOFF_CHANNEL: "claude2" });
  try {
    await main.tool("send_to_agent", { message: "task for the build agent" });
    assert.match((await main.tool("read_inbox")).content[0].text, /task for the build agent/);
    assert.equal((await worker.tool("read_inbox")).content[0].text, "No instructions yet.");

    await worker.tool("send_to_agent", { message: "task for the worker" });
    await worker.tool("post_report", { report: "worker report", status: "working" });
    assert.match((await worker.tool("read_inbox")).content[0].text, /task for the worker/);
    assert.match((await main.tool("read_inbox")).content[0].text, /task for the build agent/);
    assert.match((await main.tool("read_report")).content[0].text, /No report yet\./);
    assert.match((await worker.tool("read_report")).content[0].text, /worker report/);
    assert.ok(existsSync(path.join(workerDir, "FROM_AGENT.md")));
    assert.ok(!existsSync(path.join(mainDir, "FROM_AGENT.md")));
    assert.ok(readdirSync(workerDir).includes("TO_AGENT.md"));
  } finally {
    main.stop();
    worker.stop();
  }
});

await test("invalid channel name is refused", () => {
  const result = spawnSync(process.execPath, [server], {
    env: { ...process.env, HANDOFF_DIR: workerDir, HANDOFF_CHANNEL: "Bad Name!" },
    encoding: "utf8",
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Invalid HANDOFF_CHANNEL/);
});

await test("a channel without its own folder is refused", () => {
  const result = spawnSync(process.execPath, [server], {
    env: { ...process.env, HANDOFF_DIR: "", HANDOFF_CHANNEL: "claude2" },
    encoding: "utf8",
  });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /requires HANDOFF_DIR/);
});

rmSync(tmp, { recursive: true, force: true });
if (failures > 0) {
  console.log(`${failures} test(s) failed`);
  process.exit(1);
}
console.log("All handoff server smoke tests passed");
