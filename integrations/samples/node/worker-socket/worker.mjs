import net from "node:net";
import readline from "node:readline";

const workerId = process.env.WORKER_ID;
const workerGroupId = stringValue(process.env.MASS_WORKER_GROUP_ID ?? process.env.WORKER_GROUP_ID);
const socketHost = process.env.SOCKET_HOST ?? "127.0.0.1";
const socketPort = Number(process.env.SOCKET_PORT);

if (!workerId || !workerGroupId || !socketHost || !Number.isFinite(socketPort) || socketPort <= 0) {
  console.error("WORKER_ID, MASS_WORKER_GROUP_ID, SOCKET_HOST and positive SOCKET_PORT are required");
  process.exit(2);
}

let socket;

function log(message) {
  console.log(`[node-socket-worker:${workerId}] ${message}`);
}

function sendFrame(frame) {
  if (!socket || socket.destroyed) {
    return;
  }
  socket.write(`${JSON.stringify(frame)}\n`);
}

function buildTaskResult(taskFrame, result) {
  const resultBody = {
    status: result?.success === false ? "FAILED" : "SUCCESS",
    integrationProbe: "cross-language-node-socket",
    workerProfile: {
      runtime: "node-socket-worker",
      language: "nodejs",
      workerId,
    },
    execution: {
      transport: "socket",
      dispatchShape: "canonical-task-dispatch",
      resultShape: "canonical-task-result",
      respondedAt: new Date().toISOString(),
    },
    eventCode: taskFrame?.eventCode ?? null,
    ...(result?.result ?? {}),
  };

  return {
    frameId: `reply-${taskFrame?.replyRef ?? Date.now()}`,
    kind: "ACTION_REPLY",
    body: JSON.stringify({
      replyRef: taskFrame?.replyRef,
      success: Boolean(result?.success),
      code: result?.resultCode ?? null,
      body: JSON.stringify(resultBody),
    }),
  };
}

async function handleDemoDispatch(frame) {
  return {
    success: true,
    result: {
      detail: "completed by external node socket worker",
      taskInput: actionBody(frame),
    },
  };
}

async function handleCrawlerFetchPage(frame) {
  return {
    success: true,
    result: {
      detail: "crawler fetch simulated by external node socket worker",
      url: actionBody(frame)?.url ?? frame?.sharedConfig?.url ?? null,
      fetchedAt: new Date().toISOString(),
    },
  };
}

const taskHandlers = new Map([
  ["demo.dispatch", handleDemoDispatch],
  ["crawler.fetch-page", handleCrawlerFetchPage],
]);

function isActionDispatchFrame(frame) {
  return frame?.kind === "ACTION";
}

async function handleFrame(rawFrame) {
  const frame = JSON.parse(rawFrame);
  if (!isActionDispatchFrame(frame)) {
    log("ignoring unsupported frame");
    return;
  }

  const action = parseJson(frame.body);
  const eventCode = action?.eventCode;
  const handler = eventCode ? taskHandlers.get(eventCode) : null;
  log(`received action frame replyRef=${action?.replyRef ?? "<none>"} eventCode=${eventCode ?? "<none>"}`);

  if (!handler) {
    sendFrame(buildTaskResult(action, {
      success: false,
      resultCode: "UNSUPPORTED_EVENT_CODE",
      result: {
        detail: `Unsupported eventCode: ${eventCode ?? "<missing>"}`,
      },
    }));
    return;
  }

  const result = await handler(action);
  sendFrame(buildTaskResult(action, result));
}

function stringValue(value) {
  if (value == null) {
    return null;
  }
  const text = String(value).trim();
  return text.length > 0 ? text : null;
}

function parseJson(body) {
  try {
    return JSON.parse(body);
  } catch {
    return {};
  }
}

function actionBody(action) {
  return parseJson(action?.body);
}

function base64Url(value) {
  return Buffer.from(String(value).trim(), "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function canonicalRouteKey(groupId, subjectWorkerId) {
  return `wkr1.${base64Url(groupId)}.${base64Url(subjectWorkerId)}`;
}

function shutdown(exitCode) {
  if (socket && !socket.destroyed) {
    socket.end(() => process.exit(exitCode));
    setTimeout(() => process.exit(exitCode), 300);
    return;
  }
  process.exit(exitCode);
}

socket = net.createConnection({ host: socketHost, port: socketPort }, () => {
  log(`connected to tcp://${socketHost}:${socketPort}`);
  const routeKey = canonicalRouteKey(workerGroupId, workerId);
  sendFrame({ type: "hello", workerId, workerGroupId, routeKey });
});

const lineReader = readline.createInterface({
  input: socket,
  crlfDelay: Infinity,
});

lineReader.on("line", async (line) => {
  try {
    await handleFrame(line);
  } catch (error) {
    console.error(`[node-socket-worker:${workerId}] failed to handle frame`, error);
  }
});

socket.on("close", () => {
  log("socket closed");
});

socket.on("error", (error) => {
  console.error(`[node-socket-worker:${workerId}] socket error`, error);
});

process.on("SIGTERM", () => shutdown(0));
process.on("SIGINT", () => shutdown(0));
