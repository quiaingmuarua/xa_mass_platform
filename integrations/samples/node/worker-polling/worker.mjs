#!/usr/bin/env node

import { randomUUID } from "node:crypto";

const baseUrl = normalizeBaseUrl(process.env.MASS_BASE_URL ?? "http://127.0.0.1:8088");
const workerId = requiredEnv("MASS_WORKER_ID", "node-worker-api-001");
const workerKey = requiredEnv("MASS_WORKER_KEY", "node-worker-key");
const sessionToken = process.env.MASS_WORKER_SESSION_TOKEN ?? randomUUID();
const workerGroupId = process.env.MASS_WORKER_GROUP_ID ?? "node-runtime";
const adapterNodeId = process.env.MASS_ADAPTER_NODE_ID ?? `${workerGroupId}-node`;
const project = process.env.MASS_PROJECT ?? "crawlerApp";
const eventCode = process.env.MASS_EVENT_CODE ?? "crawler.fetch-page";
const region = process.env.MASS_REGION ?? "us";
const routingTags = splitCsv(process.env.MASS_ROUTING_TAGS ?? `web,${region}`);
const pollIntervalMs = intEnv("MASS_POLL_INTERVAL_MS", 1000);
const heartbeatIntervalMs = intEnv("MASS_HEARTBEAT_INTERVAL_MS", 10000);
const resultDelayMs = intEnv("MASS_RESULT_DELAY_MS", 5000);
const initialWorkerState = optionalEnv("MASS_INITIAL_WORKER_STATE") ?? "AVAILABLE";
const initialWorkerStateReason = process.env.MASS_INITIAL_WORKER_STATE_REASON ?? "worker-ready";
const dispatchFaultMode = optionalEnv("MASS_DISPATCH_FAULT") ?? "";

let heartbeatTimer = null;
let pollTimer = null;
let shuttingDown = false;
let receivedDispatchCount = 0;

main().catch(async (error) => {
  console.error("[worker] fatal error:", error);
  await safeOffline("node-worker-fatal-exit");
  process.exitCode = 1;
});

async function main() {
  console.log(`[worker] starting polling worker ${workerId} for ${eventCode} at ${baseUrl}`);

  await registerWorker();
  await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:online`, {
    sessionToken,
    reason: "node-worker-online",
  });
  await reportWorkerCapability();
  await reportInitialWorkerState();

  heartbeatTimer = setInterval(() => {
    post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:heartbeat`, {
      sessionToken,
      reason: "node-worker-heartbeat",
    }).catch((error) => {
      console.error("[worker] heartbeat failed:", error.message);
    });
  }, heartbeatIntervalMs);

  pollTimer = setInterval(() => {
    pollOnce().catch((error) => {
      console.error("[worker] poll failed:", error.message);
    });
  }, pollIntervalMs);

  await pollOnce();

  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

async function registerWorker() {
  const workerGroup = await post("/worker-api/v1/worker-groups", {
    groupId: workerGroupId,
    eventBindings: [
      {
        eventCode,
        projectCodes: [project],
      },
    ],
  });
  console.log("[worker] declared worker group:", workerGroup.data);

  const adapterNode = await post("/worker-api/v1/adapter-nodes", {
    adapterNodeId,
    adapterType: "polling",
    endpointId: adapterNodeId,
    attributes: {
      lang: "node",
      region,
    },
  });
  console.log("[worker] registered adapter node:", adapterNode.data);

  const binding = await post("/worker-api/v1/node-group-bindings", {
    adapterNodeId,
    workerGroupId,
    attributes: {
      region,
    },
  });
  console.log("[worker] bound adapter node to group:", binding.data);

  const response = await post("/worker-api/v1/workers", {
    workerId,
    workerGroupId,
    transportHint: "polling",
    attributes: {
      lang: "node",
      runtime: `node-${process.version}`,
      region,
      country: region,
      routingTags: routingTags.join(","),
    },
  });
  console.log("[worker] registered worker:", response.data);
}

async function reportWorkerCapability() {
  const response = await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:report-handler-evidence`, {
    eventCodes: [eventCode],
    agentVersion: `node-${process.version}`,
    attributes: {
      region,
      routingTags: routingTags.join(","),
    },
  });
  console.log("[worker] reported worker handler evidence:", response.data);
}

async function reportInitialWorkerState() {
  if (!initialWorkerState) {
    return;
  }
  const response = await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:report-runtime-evidence`, {
    state: initialWorkerState,
    reason: initialWorkerStateReason,
    attributes: {
      source: "node-polling-worker",
      region,
    },
  });
  console.log("[worker] reported worker runtime evidence:", response.data);
}

async function pollOnce() {
  const response = await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:poll`, {
    maxMessages: 10,
  });
  const items = response?.data?.items ?? [];
  if (!Array.isArray(items) || items.length === 0) {
    return;
  }
  for (const item of items) {
    await handleDispatch(item);
  }
}

async function handleDispatch(item) {
  const { replyRef, eventCode: dispatchEventCode } = item;
  console.log(`[worker] received replyRef=${replyRef} eventCode=${dispatchEventCode}`);
  receivedDispatchCount += 1;
  if (dispatchFaultMode === "exit-before-result" && receivedDispatchCount === 1) {
    console.log(`[worker] fault exit-before-result replyRef=${replyRef}`);
    clearRuntimeTimers();
    process.exit(2);
  }

  let result;
  try {
    result = await dispatchByEventCode(item);
  } catch (error) {
    result = {
      success: false,
      code: "WORKER_HANDLER_ERROR",
      body: {
        detail: error instanceof Error ? error.message : String(error),
        workerId,
        eventCode: dispatchEventCode,
      },
    };
  }

  if (dispatchFaultMode === "late-result-after-lease-expiry" && receivedDispatchCount === 1) {
    console.log(`[worker] fault late-result-after-lease-expiry replyRef=${replyRef} delayMs=${resultDelayMs}`);
    clearRuntimeTimers();
    await safeOffline("node-worker-late-result-replay");
    await sleep(resultDelayMs);
    const response = await submitResult(replyRef, result);
    console.log("[worker] fault late-result-after-lease-expiry submitted result:", response.data);
    process.exit(0);
  }

  const response = await submitResult(replyRef, result);
  console.log("[worker] submitted result:", response.data);
}

async function submitResult(replyRef, result) {
  return post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:submit-result`, {
    replyRef,
    success: result.success,
    code: result.code ?? null,
    body: JSON.stringify(result.body ?? {}),
  });
}

async function dispatchByEventCode(item) {
  switch (item.eventCode) {
    case "crawler.fetch-page":
      return handleCrawlerFetchPage(item);
    default:
      throw new Error(`Unsupported eventCode: ${item.eventCode}`);
  }
}

async function handleCrawlerFetchPage(item) {
  const body = parseJson(item?.body);
  const url = body?.url ?? item?.sharedConfig?.url;
  if (!url || typeof url !== "string") {
    return {
      success: false,
      code: "INVALID_INPUT",
      body: {
        detail: "url is required in WorkerAction.body.url",
        workerId,
        eventCode: item.eventCode,
      },
    };
  }

  const startedAt = Date.now();
  try {
    const response = await fetch(url);
    const body = await response.text();
    const resultBody = {
      workerId,
      eventCode: item.eventCode,
      url: response.url,
      statusCode: response.status,
      title: extractHtmlTitle(body),
      fetchedAt: new Date().toISOString(),
      elapsedMs: Date.now() - startedAt,
    };
    return {
      success: response.ok,
      code: response.ok ? null : `HTTP_${response.status}`,
      body: {
        detail: response.ok ? "crawler-success" : `crawler-http-${response.status}`,
        ...resultBody,
      },
    };
  } catch (error) {
    return {
      success: false,
      code: "FETCH_ERROR",
      body: {
        detail: error instanceof Error ? error.message : String(error),
        workerId,
        eventCode: item.eventCode,
        url,
        fetchedAt: new Date().toISOString(),
        elapsedMs: Date.now() - startedAt,
      },
    };
  }
}

function parseJson(value) {
  if (typeof value !== "string" || value.length === 0) {
    return {};
  }
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

async function post(path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Mass-Api-Key": workerKey,
    },
    body: JSON.stringify(body ?? {}),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok || json.code !== 0) {
    throw new Error(`HTTP ${response.status} ${path}: ${json.msg ?? "unknown error"}`);
  }
  return json;
}

function extractHtmlTitle(html) {
  if (typeof html !== "string" || html.length === 0) {
    return null;
  }
  const match = html.match(/<title[^>]*>([^<]+)<\/title>/i);
  return match ? match[1].trim() : null;
}

function normalizeBaseUrl(value) {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function requiredEnv(name, fallback) {
  const value = process.env[name] ?? fallback;
  if (!value || String(value).trim().length === 0) {
    throw new Error(`${name} is required`);
  }
  return String(value).trim();
}

function optionalEnv(name) {
  const value = process.env[name];
  if (value == null) {
    return null;
  }
  const normalized = String(value).trim();
  return normalized.length === 0 ? null : normalized;
}

function intEnv(name, fallback) {
  const value = Number.parseInt(process.env[name] ?? `${fallback}`, 10);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

function splitCsv(value) {
  return String(value)
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean);
}

function sleep(delayMillis) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, delayMillis)));
}

async function shutdown(signal) {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  console.log(`[worker] shutting down on ${signal}`);
  clearRuntimeTimers();
  await safeOffline(`node-worker-${signal.toLowerCase()}`);
  process.exit(0);
}

function clearRuntimeTimers() {
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
    heartbeatTimer = null;
  }
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

async function safeOffline(reason) {
  try {
    await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:offline`, { sessionToken, reason });
  } catch (error) {
    console.error("[worker] offline failed:", error.message);
  }
}
