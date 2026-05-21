#!/usr/bin/env node

const baseUrl = normalizeBaseUrl(process.env.MASS_BASE_URL ?? "http://127.0.0.1:8088");
const workerId = requiredEnv("MASS_WORKER_ID", "node-worker-api-001");
const workerKey = requiredEnv("MASS_WORKER_KEY", "node-worker-key");
const workerGroupId = process.env.MASS_WORKER_GROUP_ID ?? "node-runtime";
const adapterNodeId = process.env.MASS_ADAPTER_NODE_ID ?? `${workerGroupId}-node`;
const project = process.env.MASS_PROJECT ?? "crawlerApp";
const eventCode = process.env.MASS_EVENT_CODE ?? "crawler.fetch-page";
const region = process.env.MASS_REGION ?? "us";
const routingTags = splitCsv(process.env.MASS_ROUTING_TAGS ?? `web,${region}`);
const pollIntervalMs = intEnv("MASS_POLL_INTERVAL_MS", 1000);
const heartbeatIntervalMs = intEnv("MASS_HEARTBEAT_INTERVAL_MS", 10000);
const initialWorkerState = optionalEnv("MASS_INITIAL_WORKER_STATE") ?? "AVAILABLE";
const initialWorkerStateReason = process.env.MASS_INITIAL_WORKER_STATE_REASON ?? "worker-ready";

let heartbeatTimer = null;
let pollTimer = null;
let shuttingDown = false;

main().catch(async (error) => {
  console.error("[worker] fatal error:", error);
  await safeOffline("node-worker-fatal-exit");
  process.exitCode = 1;
});

async function main() {
  console.log(`[worker] starting polling worker ${workerId} for ${eventCode} at ${baseUrl}`);

  await registerWorker();
  await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:online`, {
    reason: "node-worker-online",
  });
  await reportInitialWorkerState();

  heartbeatTimer = setInterval(() => {
    post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:heartbeat`, {
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
    adapterNodeId,
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

async function reportInitialWorkerState() {
  if (!initialWorkerState) {
    return;
  }
  const response = await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:report-state`, {
    state: initialWorkerState,
    reason: initialWorkerStateReason,
    attributes: {
      source: "node-polling-worker",
      region,
    },
  });
  console.log("[worker] reported worker state:", response.data);
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
  const { taskId, messageId, eventCode: dispatchEventCode } = item;
  console.log(`[worker] received taskId=${taskId} messageId=${messageId} eventCode=${dispatchEventCode}`);

  let result;
  try {
    result = await dispatchByEventCode(item);
  } catch (error) {
    result = {
      success: false,
      detail: error instanceof Error ? error.message : String(error),
      errorCode: "WORKER_HANDLER_ERROR",
      output: {
        workerId,
        eventCode: dispatchEventCode,
      },
    };
  }

  const response = await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:submit-result`, {
    taskId,
    messageId,
    success: result.success,
    detail: result.detail,
    errorCode: result.errorCode ?? null,
    output: result.output ?? {},
  });
  console.log("[worker] submitted result:", response.data);
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
  const url = item?.input?.url ?? item?.sharedConfig?.url;
  if (!url || typeof url !== "string") {
    return {
      success: false,
      detail: "url is required in TaskDispatchItem.input.url",
      errorCode: "INVALID_INPUT",
      output: {
        workerId,
        eventCode: item.eventCode,
      },
    };
  }

  const startedAt = Date.now();
  try {
    const response = await fetch(url);
    const body = await response.text();
    const output = {
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
      detail: response.ok ? "crawler-success" : `crawler-http-${response.status}`,
      errorCode: response.ok ? null : `HTTP_${response.status}`,
      output,
    };
  } catch (error) {
    return {
      success: false,
      detail: error instanceof Error ? error.message : String(error),
      errorCode: "FETCH_ERROR",
      output: {
        workerId,
        eventCode: item.eventCode,
        url,
        fetchedAt: new Date().toISOString(),
        elapsedMs: Date.now() - startedAt,
      },
    };
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

async function shutdown(signal) {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;
  console.log(`[worker] shutting down on ${signal}`);
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer);
  }
  if (pollTimer) {
    clearInterval(pollTimer);
  }
  await safeOffline(`node-worker-${signal.toLowerCase()}`);
  process.exit(0);
}

async function safeOffline(reason) {
  try {
    await post(`/worker-api/v1/workers/${encodeURIComponent(workerId)}:offline`, { reason });
  } catch (error) {
    console.error("[worker] offline failed:", error.message);
  }
}
