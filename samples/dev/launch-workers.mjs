#!/usr/bin/env node

import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const baseUrl = normalizeBaseUrl(process.env.MASS_BASE_URL ?? "http://127.0.0.1:8088");
const wsUrl = requiredEnv("MASS_WS_URL");
const nodeBin = process.env.NODE_BIN ?? process.execPath;
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const bootstrapConfigPath = resolve(repoRoot, "samples/dev/bootstrap.json");
const ruleConfigPath = resolve(repoRoot, "samples/dev/rules.json");
const workerConfigPath = resolve(repoRoot, "samples/dev/workers.json");
const taskConfigPath = resolve(repoRoot, "samples/dev/tasks.json");
const taskSubmitterKey = process.env.MASS_TASK_SUBMITTER_KEY ?? "crawler-submitter-key";
const bootstrapKey = process.env.SAMPLE_BOOTSTRAP_KEY ?? "dev-bootstrap-key";

const children = [];
let shuttingDown = false;
let keepAliveTimer = null;

main().catch(async (error) => {
  console.error("[sample-launcher] fatal error:", error);
  await shutdown(1);
});

async function main() {
  const bootstrapSpec = await readJson(bootstrapConfigPath);
  const ruleSpec = await readJson(ruleConfigPath);
  const workerSpecs = await readJson(workerConfigPath);
  const taskSpecs = await readJson(taskConfigPath);
  await bootstrapCatalog(bootstrapSpec);
  await bootstrapRules(ruleSpec);
  console.log(`[sample-launcher] registering and starting ${workerSpecs.length} sample workers`);
  for (const spec of workerSpecs) {
    await registerWorker(spec);
    if (spec.context) {
      await registerWorkerContext(spec);
    }
    startWorker(spec);
  }
  for (const spec of workerSpecs) {
    await waitForWorkerOnline(spec.workerId);
  }
  await seedTasks(taskSpecs);

  process.on("SIGINT", () => void shutdown(0));
  process.on("SIGTERM", () => void shutdown(0));

  keepAliveTimer = setInterval(() => {}, 60_000);
}

async function bootstrapCatalog(spec) {
  const response = await post("/sample-api/bootstrap/catalog", bootstrapKey, spec, "X-Sample-Bootstrap-Key");
  console.log(`[sample-launcher] bootstrapped catalog: ${JSON.stringify(response.data)}`);
}

async function bootstrapRules(spec) {
  const response = await post("/sample-api/bootstrap/rules", bootstrapKey, spec, "X-Sample-Bootstrap-Key");
  console.log(`[sample-launcher] bootstrapped rules: ${JSON.stringify(response.data)}`);
}

function startWorker(spec) {
  const scriptPath = resolve(repoRoot, spec.scriptPath);
  const child = spawn(nodeBin, [scriptPath], {
    cwd: repoRoot,
    env: {
      ...process.env,
      WORKER_ID: spec.workerId,
      WS_URL: wsUrl,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk) => forwardLogs(spec.workerId, chunk));
  child.stderr.on("data", (chunk) => forwardLogs(spec.workerId, chunk));
  child.on("exit", (code, signal) => {
    console.log(`[sample-launcher] worker ${spec.workerId} exited code=${code ?? ""} signal=${signal ?? ""}`);
    if (!shuttingDown && code && code !== 0) {
      console.error(`[sample-launcher] worker ${spec.workerId} exited unexpectedly`);
    }
  });
  children.push(child);
  console.log(`[sample-launcher] started worker ${spec.workerId} script=${spec.scriptPath}`);
}

async function registerWorker(spec) {
  const response = await post("/worker-api/workers/register", spec.workerKey, {
    workerId: spec.workerId,
    workerGroupId: spec.workerGroupId,
    adapterId: spec.adapterId ?? "websocket",
    transportHint: spec.transportHint ?? "realtime",
    attributes: spec.attributes,
    eventBindings: spec.eventBindings,
  });
  console.log(`[sample-launcher] registered worker ${spec.workerId}: ${JSON.stringify(response.data)}`);
}

async function registerWorkerContext(spec) {
  const response = await post("/worker-api/worker-contexts/register", spec.workerKey, {
    workerContextId: spec.context.workerContextId,
    workerId: spec.workerId,
    project: spec.context.project,
    routingTags: spec.context.routingTags,
    attributes: spec.context.attributes,
  });
  console.log(`[sample-launcher] registered worker context ${spec.context.workerContextId}: ${JSON.stringify(response.data)}`);
}

async function seedTasks(taskSpecs) {
  if (!Array.isArray(taskSpecs) || taskSpecs.length === 0) {
    console.log("[sample-launcher] no external seed tasks configured");
    return;
  }
  for (const taskSpec of taskSpecs) {
    const requestBody = replacePlaceholders(taskSpec.body ?? {}, {
      MASS_BASE_URL: baseUrl,
      MASS_WS_URL: wsUrl,
    });
    const createResponse = await post("/status/api/tasks", taskSubmitterKey, requestBody);
    const taskId = String(createResponse.data?.taskId ?? "");
    console.log(`[sample-launcher] created seed task ${requestBody.taskName}: ${taskId}`);
    if (taskSpec.approve && taskId) {
      await post(`/status/api/tasks/${encodeURIComponent(taskId)}/audit?approved=true&comment=sample-launcher`, taskSubmitterKey, null);
      console.log(`[sample-launcher] approved seed task ${requestBody.taskName}: ${taskId}`);
    }
  }
}

async function post(path, apiKey, body, headerName = "X-Mass-Api-Key") {
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      [headerName]: apiKey,
    },
    body: body == null ? null : JSON.stringify(body),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok || json.code !== 0) {
    throw new Error(`HTTP ${response.status} ${path}: ${json.msg ?? "unknown error"}`);
  }
  return json;
}

async function waitForWorkerOnline(workerId) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = await fetch(`${baseUrl}/status/api/workers`);
    const json = await response.json().catch(() => ({}));
    const items = json?.data?.items;
    if (Array.isArray(items)) {
      const worker = items.find((item) => item?.workerId === workerId);
      if (worker?.status === "ONLINE") {
        console.log(`[sample-launcher] worker online ${workerId}`);
        return;
      }
    }
    await sleep(250);
  }
  throw new Error(`worker did not reach ONLINE: ${workerId}`);
}

async function readJson(path) {
  const text = await readFile(path, "utf8");
  return JSON.parse(text);
}

function replacePlaceholders(value, replacements) {
  if (typeof value === "string") {
    let result = value;
    for (const [key, replacement] of Object.entries(replacements)) {
      result = result.replaceAll(`\${${key}}`, replacement);
    }
    return result;
  }
  if (Array.isArray(value)) {
    return value.map((item) => replacePlaceholders(item, replacements));
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, entryValue]) => [key, replacePlaceholders(entryValue, replacements)]),
    );
  }
  return value;
}

function forwardLogs(workerId, chunk) {
  String(chunk)
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.length > 0)
    .forEach((line) => console.log(`[sample-launcher:${workerId}] ${line}`));
}

async function shutdown(exitCode) {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  if (keepAliveTimer) {
    clearInterval(keepAliveTimer);
  }
  await Promise.all(children.map((child) => stopChild(child)));
  process.exit(exitCode);
}

async function stopChild(child) {
  if (!child || child.exitCode != null) {
    return;
  }
  child.kill("SIGTERM");
  await new Promise((resolveDone) => {
    const timeout = setTimeout(() => {
      if (child.exitCode == null) {
        child.kill("SIGKILL");
      }
    }, 5_000);
    child.once("exit", () => {
      clearTimeout(timeout);
      resolveDone();
    });
  });
}

function sleep(ms) {
  return new Promise((resolveDone) => setTimeout(resolveDone, ms));
}

function normalizeBaseUrl(value) {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function requiredEnv(name) {
  const value = process.env[name];
  if (!value || String(value).trim().length === 0) {
    throw new Error(`${name} is required`);
  }
  return String(value).trim();
}
