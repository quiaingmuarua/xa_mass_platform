#!/usr/bin/env node

import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const args = parseArgs(process.argv.slice(2));
if (args.help) {
  printHelp();
  process.exit(0);
}

const baseUrl = normalizeBaseUrl(args.baseUrl ?? process.env.MASS_BASE_URL ?? "http://127.0.0.1:8088");
const wsUrl = args.wsUrl ?? process.env.MASS_WS_URL ?? "ws://127.0.0.1:18088/ws";
const nodeBin = process.env.NODE_BIN ?? process.execPath;
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..", "..");
const workerConfigPath = resolve(repoRoot, "integrations/samples/dev/scenario/workers.json");
const taskConfigPath = resolve(repoRoot, "integrations/samples/dev/scenario/tasks.json");
const taskSubmitterKey = process.env.MASS_TASK_SUBMITTER_KEY ?? "crawler-submitter-key";

const children = [];
let shuttingDown = false;
let keepAliveTimer = null;
const declaredWorkerGroups = new Set();
const registeredAdapterNodes = new Set();
const boundAdapterNodeGroups = new Set();

main().catch(async (error) => {
  console.error("[sample-launcher] fatal error:", error);
  await shutdown(1);
});

async function main() {
  const workerSpecs = expandWorkerSpecs(await readJson(workerConfigPath));
  const taskSpecs = expandTaskSpecs(await readJson(taskConfigPath));
  const managedWorkerSpecs = workerSpecs.filter((spec) => spec.startMode !== "api-online");
  const apiOnlineWorkerSpecs = workerSpecs.filter((spec) => spec.startMode === "api-online");
  const approvedTaskSpecs = taskSpecs.filter((spec) => spec.approve === true);
  const stagedTaskSpecs = taskSpecs.filter((spec) => spec.approve !== true);
  console.log(`[sample-launcher] mode=${args.registerOnly ? "register-only" : "launch"} baseUrl=${baseUrl} wsUrl=${wsUrl}`);
  console.log("[sample-launcher] using initialized server catalog, rules, and credentials");
  console.log(`[sample-launcher] registering ${workerSpecs.length} sample workers`);
  for (const spec of managedWorkerSpecs) {
    await registerWorker(spec);
    if (!args.registerOnly) {
      startWorker(spec);
    } else {
      console.log(`[sample-launcher] worker ${spec.workerId} registered only; external transport process not started`);
    }
  }
  if (!args.registerOnly) {
    for (const spec of managedWorkerSpecs) {
      await waitForWorkerOnline(spec);
    }
  }
  await seedTasks(approvedTaskSpecs);
  await runWithConcurrency(apiOnlineWorkerSpecs, 12, async (spec) => {
    await registerWorker(spec);
    startWorker(spec);
  });
  await runWithConcurrency(apiOnlineWorkerSpecs, 24, async (spec) => {
    await waitForWorkerOnline(spec);
  });
  await seedTasks(stagedTaskSpecs);

  if (args.registerOnly) {
    console.log("[sample-launcher] register-only complete");
    return;
  }

  process.on("SIGINT", () => void shutdown(0));
  process.on("SIGTERM", () => void shutdown(0));
  keepAliveTimer = setInterval(() => {}, 60_000);
}

function startWorker(spec) {
  if (spec.startMode === "api-online") {
    console.log(`[sample-launcher] worker ${spec.workerId} registered online through worker API`);
    return;
  }
  if (!spec.scriptPath) {
    console.log(`[sample-launcher] worker ${spec.workerId} registered without a managed process`);
    return;
  }
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
  const adapterNodeId = adapterNodeIdFor(spec);
  const adapterType = spec.adapterId ?? "websocket";

  if (!declaredWorkerGroups.has(spec.workerGroupId)) {
    const groupResponse = await post("/worker-api/v1/worker-groups", spec.workerKey, {
      groupId: spec.workerGroupId,
      eventBindings: spec.eventBindings,
    });
    declaredWorkerGroups.add(spec.workerGroupId);
    console.log(`[sample-launcher] declared worker group ${spec.workerGroupId}: ${JSON.stringify(groupResponse.data)}`);
  }

  if (!registeredAdapterNodes.has(adapterNodeId)) {
    const adapterNodeResponse = await post("/worker-api/v1/adapter-nodes", spec.workerKey, {
      adapterNodeId,
      adapterType,
      endpointId: adapterNodeId,
      attributes: {
        launcher: "integrations/samples/dev/scenario/launch-workers.mjs",
        transport: adapterType,
      },
    });
    registeredAdapterNodes.add(adapterNodeId);
    console.log(`[sample-launcher] registered adapter node ${adapterNodeId}: ${JSON.stringify(adapterNodeResponse.data)}`);
  }

  const bindingKey = `${adapterNodeId}\n${spec.workerGroupId}`;
  if (!boundAdapterNodeGroups.has(bindingKey)) {
    const bindingResponse = await post("/worker-api/v1/node-group-bindings", spec.workerKey, {
      adapterNodeId,
      workerGroupId: spec.workerGroupId,
      attributes: {
        transport: adapterType,
      },
    });
    boundAdapterNodeGroups.add(bindingKey);
    console.log(`[sample-launcher] bound adapter node ${adapterNodeId} to group ${spec.workerGroupId}: ${JSON.stringify(bindingResponse.data)}`);
  }

  const response = await post("/worker-api/v1/workers", spec.workerKey, {
    workerId: spec.workerId,
    adapterNodeId,
    workerGroupId: spec.workerGroupId,
    adapterId: adapterType,
    transportHint: spec.transportHint ?? "realtime",
    attributes: spec.attributes,
  });
  console.log(`[sample-launcher] registered worker ${spec.workerId}: ${JSON.stringify(response.data)}`);
  if (spec.startMode === "api-online") {
    await post(`/worker-api/v1/workers/${encodeURIComponent(spec.workerId)}:online`, spec.workerKey, {
      reason: "sample-launcher-api-online",
    });
    await post(`/worker-api/v1/workers/${encodeURIComponent(spec.workerId)}:report-capability`, spec.workerKey, {
      availableEventCodes: [...new Set((spec.eventBindings ?? []).map((binding) => binding.eventCode).filter(Boolean))],
      agentVersion: "sample-launcher-api-online",
      schedulingAttributes: spec.attributes ?? {},
    });
    await post(`/worker-api/v1/workers/${encodeURIComponent(spec.workerId)}:report-state`, spec.workerKey, {
      state: "AVAILABLE",
      reason: "sample-launcher-api-online",
      observedAt: new Date().toISOString(),
      attributes: {
        source: "sample-launcher",
        ...(spec.attributes ?? {}),
      },
    });
  }
}

function adapterNodeIdFor(spec) {
  if (typeof spec.adapterNodeId === "string" && spec.adapterNodeId.trim().length > 0) {
    return spec.adapterNodeId.trim();
  }
  const adapterId = typeof spec.adapterId === "string" && spec.adapterId.trim().length > 0
    ? spec.adapterId.trim()
    : "websocket";
  return `sample-${adapterId}-node`;
}

async function seedTasks(taskSpecs) {
  if (!Array.isArray(taskSpecs) || taskSpecs.length === 0) {
    console.log("[sample-launcher] no external seed tasks configured");
    return;
  }
  for (const taskSpec of taskSpecs) {
    const taskApiKey = taskSpec.apiKey ?? taskSubmitterKey;
    const requestBody = replacePlaceholders(taskSpec.body ?? {}, {
      MASS_BASE_URL: baseUrl,
      MASS_WS_URL: wsUrl,
    });
    const shellRequest = {
      userId: requestBody.userId,
      project: requestBody.project,
      sharedConfig: requestBody.sharedConfig,
      executionSpec: requestBody.executionSpec ?? normalizeExecutionSpec(requestBody),
      sourceType: requestBody.sourceType,
      sourceRef: requestBody.sourceRef,
    };
    const createResponse = await post("/api/v1/tasks", taskApiKey, {
      ...shellRequest,
    });
    const taskId = String(createResponse.data?.taskId ?? "");
    console.log(
      `[sample-launcher] created seed task project=${requestBody.project} event=${requestBody.eventCode ?? ""}: ${taskId}`,
    );
    if (Array.isArray(requestBody.items) && requestBody.items.length > 0) {
      const itemBatchSize = Number.isInteger(taskSpec.itemBatchSize) && taskSpec.itemBatchSize > 0
        ? taskSpec.itemBatchSize
        : 500;
      for (const items of chunks(requestBody.items, itemBatchSize)) {
        await post(`/api/v1/tasks/${encodeURIComponent(taskId)}/items`, taskApiKey, {
          eventCode: requestBody.eventCode,
          items,
        });
      }
    }
    if (!requestBody.keepIntakeOpen) {
      await executeTaskCommand(taskId, "SEAL", taskApiKey);
    }
    if (taskSpec.approve && taskId) {
      await executeTaskCommand(taskId, "APPROVE", taskApiKey);
      console.log(
        `[sample-launcher] approved seed task project=${requestBody.project} event=${requestBody.eventCode ?? ""}: ${taskId}`,
      );
    }
  }
}

async function executeTaskCommand(taskId, command, apiKey = taskSubmitterKey) {
  await post(`/api/v1/tasks/${encodeURIComponent(taskId)}/commands`, apiKey, { command });
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

async function waitForWorkerOnline(spec) {
  const workerId = spec.workerId;
  const requiresTransportPresence = spec.startMode !== "api-online" && (spec.transportHint ?? "realtime") !== "polling";
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const response = await fetch(`${baseUrl}/api/v1/catalog/worker-capabilities`);
    const json = await response.json().catch(() => ({}));
    const items = json?.data;
    if (Array.isArray(items)) {
      const worker = items.find((item) => item?.workerId === workerId);
      const transportOnline = worker?.transportOnline === true || worker?.online === true || worker?.hasActiveEndpoint === true;
      const modelOnline = worker?.status === "ONLINE";
      if ((requiresTransportPresence && transportOnline) || (!requiresTransportPresence && (transportOnline || modelOnline))) {
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

async function runWithConcurrency(items, concurrency, worker) {
  if (!Array.isArray(items) || items.length === 0) {
    return;
  }
  const limit = Math.max(1, Math.min(concurrency, items.length));
  let nextIndex = 0;
  const runners = Array.from({ length: limit }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      await worker(items[index], index);
    }
  });
  await Promise.all(runners);
}

function chunks(items, chunkSize) {
  const normalizedChunkSize = Math.max(1, chunkSize);
  const result = [];
  for (let index = 0; index < items.length; index += normalizedChunkSize) {
    result.push(items.slice(index, index + normalizedChunkSize));
  }
  return result;
}

function expandWorkerSpecs(specs) {
  if (!Array.isArray(specs)) {
    return [];
  }
  return specs.flatMap((spec) => expandCountedSpec(spec));
}

function expandTaskSpecs(specs) {
  if (!Array.isArray(specs)) {
    return [];
  }
  return specs.map((spec) => {
    const body = { ...(spec.body ?? {}) };
    const generatedItems = spec.generatedItems;
    if (generatedItems && Number.isInteger(generatedItems.count) && generatedItems.count > 0) {
      const generated = [];
      for (let index = 0; index < generatedItems.count; index += 1) {
        generated.push(replacePlaceholders(generatedItems.template ?? {}, placeholderValues(index)));
      }
      body.items = [...(Array.isArray(body.items) ? body.items : []), ...generated];
    }
    const { generatedItems: ignored, ...rest } = spec;
    return { ...rest, body };
  });
}

function expandCountedSpec(spec) {
  const count = Number.isInteger(spec?.count) && spec.count > 0 ? spec.count : 1;
  const { count: ignored, ...template } = spec;
  const expanded = [];
  for (let index = 0; index < count; index += 1) {
    expanded.push(replacePlaceholders(template, placeholderValues(index)));
  }
  return expanded;
}

function placeholderValues(index) {
  const regions = ["us", "gb", "de", "fr", "sg", "jp"];
  const fingerprints = ["fp-sg-alpha", "fp-sg-beta", "fp-sg-gamma", "fp-sg-delta"];
  const mccMncs = ["52501", "52505"];
  return {
    INDEX: String(index),
    INDEX1: String(index + 1),
    PAD3: String(index + 1).padStart(3, "0"),
    PAD5: String(index + 1).padStart(5, "0"),
    PAD6: String(index + 1).padStart(6, "0"),
    REGION: regions[index % regions.length],
    FINGERPRINT: fingerprints[index % fingerprints.length],
    MCC_MNC: mccMncs[index % mccMncs.length],
  };
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

function normalizeExecutionSpec(requestBody) {
  const spec = {};
  if (Number.isInteger(requestBody.batchSize) && requestBody.batchSize > 0) {
    spec.batchSize = requestBody.batchSize;
  }
  if (typeof requestBody.workloadClass === "string" && requestBody.workloadClass.length > 0) {
    spec.workloadClass = requestBody.workloadClass;
  }
  if (Number.isInteger(requestBody.maxRuntimeSeconds) && requestBody.maxRuntimeSeconds >= 0) {
    spec.maxRuntimeSeconds = requestBody.maxRuntimeSeconds;
  }
  return Object.keys(spec).length === 0 ? undefined : spec;
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

function parseArgs(argv) {
  const parsed = {
    baseUrl: null,
    wsUrl: null,
    help: false,
    registerOnly: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--help" || arg === "-h") {
      parsed.help = true;
    } else if (arg === "--register-only") {
      parsed.registerOnly = true;
    } else if (arg === "--base-url") {
      parsed.baseUrl = requiredArg(argv, index, arg);
      index += 1;
    } else if (arg.startsWith("--base-url=")) {
      parsed.baseUrl = arg.slice("--base-url=".length);
    } else if (arg === "--ws-url") {
      parsed.wsUrl = requiredArg(argv, index, arg);
      index += 1;
    } else if (arg.startsWith("--ws-url=")) {
      parsed.wsUrl = arg.slice("--ws-url=".length);
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  return parsed;
}

function requiredArg(argv, index, name) {
  const value = argv[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`${name} requires a value`);
  }
  return value;
}

function printHelp() {
  console.log(`Usage:
  node integrations/samples/dev/scenario/launch-workers.mjs [options]

Options:
  --register-only       Register catalog, rules, workers, and tasks, then exit.
                        Realtime worker processes are not started.
  --base-url <url>      Server HTTP base URL. Default: MASS_BASE_URL or http://127.0.0.1:8088
  --ws-url <url>        WebSocket URL for full launch. Default: MASS_WS_URL or ws://127.0.0.1:18088/ws
  -h, --help            Show this help.
`);
}
