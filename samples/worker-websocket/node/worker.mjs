const workerId = process.env.WORKER_ID;
const wsUrl = process.env.WS_URL;

if (!workerId || !wsUrl) {
  console.error("WORKER_ID and WS_URL are required");
  process.exit(2);
}

if (typeof WebSocket !== "function") {
  console.error("This Node.js runtime does not provide a global WebSocket client");
  process.exit(2);
}

let socket;

function log(message) {
  console.log(`[node-worker:${workerId}] ${message}`);
}

function buildTaskResult(taskFrame, result) {
  return JSON.stringify({
    messageId: taskFrame?.messageId,
    taskId: taskFrame?.taskId,
    success: Boolean(result?.success),
    detail: result?.detail ?? "completed by external node worker",
    errorCode: result?.errorCode ?? null,
    output: {
      status: result?.success === false ? "FAILED" : "SUCCESS",
      message: result?.detail ?? "completed by external node worker",
      integrationProbe: "cross-language-node",
      workerProfile: {
        runtime: "node-websocket-worker",
        language: "nodejs",
        workerId,
      },
      execution: {
        transport: "websocket",
        dispatchShape: "canonical-task-dispatch",
        resultShape: "canonical-task-result",
        respondedAt: new Date().toISOString(),
      },
      eventCode: taskFrame?.eventCode ?? null,
      ...(result?.output ?? {}),
    },
  });
}

async function handleDemoDispatch(frame) {
  return {
    success: true,
    detail: "completed by external node worker",
    output: {
      taskInput: frame?.input ?? {},
    },
  };
}

async function handleCrawlerFetchPage(frame) {
  return {
    success: true,
    detail: "crawler fetch simulated by external node websocket worker",
    output: {
      url: frame?.input?.url ?? frame?.sharedConfig?.url ?? null,
      fetchedAt: new Date().toISOString(),
    },
  };
}

const taskHandlers = new Map([
  ["demo.dispatch", handleDemoDispatch],
  ["crawler.fetch-page", handleCrawlerFetchPage],
]);

function isControlCompatibilityFrame(frame) {
  return Boolean(frame?.eventCode) && !frame?.taskId;
}

function isCanonicalTaskDispatch(frame) {
  return Boolean(frame?.taskId) && Boolean(frame?.messageId) && frame?.success === undefined;
}

async function handleFrame(rawFrame) {
  const frame = JSON.parse(rawFrame);

  if (isControlCompatibilityFrame(frame)) {
    log(`ignoring control compatibility frame eventCode=${frame.eventCode}`);
    return;
  }

  if (!isCanonicalTaskDispatch(frame)) {
    log("ignoring unsupported frame");
    return;
  }

  const eventCode = frame?.eventCode;
  const handler = eventCode ? taskHandlers.get(eventCode) : null;
  log(`received task frame taskId=${frame.taskId} messageId=${frame.messageId} eventCode=${eventCode ?? "<none>"}`);

  if (!handler) {
    socket.send(buildTaskResult(frame, {
      success: false,
      detail: `Unsupported eventCode: ${eventCode ?? "<missing>"}`,
      errorCode: "UNSUPPORTED_EVENT_CODE",
      output: {},
    }));
    return;
  }

  const result = await handler(frame);
  socket.send(buildTaskResult(frame, result));
}

function shutdown(exitCode) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.close(1000, "shutdown");
    setTimeout(() => process.exit(exitCode), 200);
    return;
  }

  process.exit(exitCode);
}

socket = new WebSocket(`${wsUrl}${wsUrl.includes("?") ? "&" : "?"}workerId=${encodeURIComponent(workerId)}`);

socket.addEventListener("open", () => {
  log(`connected to ${socket.url}`);
});

socket.addEventListener("message", async (event) => {
  const rawFrame = typeof event.data === "string" ? event.data : String(event.data);
  try {
    await handleFrame(rawFrame);
  } catch (error) {
    console.error(`[node-worker:${workerId}] failed to handle frame`, error);
  }
});

socket.addEventListener("close", (event) => {
  log(`socket closed code=${event.code} reason=${event.reason || ""}`);
});

socket.addEventListener("error", (event) => {
  console.error(`[node-worker:${workerId}] websocket error`, event?.message ?? event);
});

process.on("SIGTERM", () => shutdown(0));
process.on("SIGINT", () => shutdown(0));
