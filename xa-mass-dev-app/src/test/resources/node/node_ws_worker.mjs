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

function buildTaskResult(taskFrame) {
  return JSON.stringify({
    messageId: taskFrame?.msgId ?? taskFrame?.messageId,
    taskId: taskFrame?.context?.taskId,
    success: true,
    detail: "completed by external node worker",
    output: {
      status: "SUCCESS",
      message: "completed by external node worker",
      integrationProbe: "cross-language-node",
      workerProfile: {
        runtime: "node-websocket-worker",
        language: "nodejs",
        workerId,
      },
      execution: {
        transport: "websocket",
        dispatchShape: "task-step-compat-shell",
        resultShape: "canonical-task-result",
        respondedAt: new Date().toISOString(),
      },
    },
  });
}

function handleFrame(rawFrame) {
  const frame = JSON.parse(rawFrame);

  if (frame?.eventCode) {
    log(`ignoring control frame eventCode=${frame.eventCode}`);
    return;
  }

  if (frame?.msgType === "TASK" && frame?.response !== true) {
    log(`received task frame taskId=${frame?.context?.taskId ?? "unknown"} msgId=${frame?.msgId ?? "unknown"}`);
    socket.send(buildTaskResult(frame));
    return;
  }

  log(`ignoring frame msgType=${frame?.msgType ?? "unknown"}`);
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

socket.addEventListener("message", (event) => {
  const rawFrame = typeof event.data === "string" ? event.data : String(event.data);
  try {
    handleFrame(rawFrame);
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
