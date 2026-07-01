import net from "node:net";
import readline from "node:readline";

const workerId = process.env.WORKER_ID;
const workerGroupId = stringValue(process.env.MASS_WORKER_GROUP_ID ?? process.env.WORKER_GROUP_ID);
const socketHost = process.env.SOCKET_HOST ?? "127.0.0.1";
const socketPort = Number(process.env.SOCKET_PORT);
const dispatchFaultMode = stringValue(process.env.MASS_DISPATCH_FAULT) ?? "";
const resultDelayMs = intEnv("MASS_RESULT_DELAY_MS", 5000);

if (!workerId || !workerGroupId || !socketHost || !Number.isFinite(socketPort) || socketPort <= 0) {
  console.error("WORKER_ID, MASS_WORKER_GROUP_ID, SOCKET_HOST and positive SOCKET_PORT are required");
  process.exit(2);
}

let socket;
let receivedActionCount = 0;

function log(message) {
  console.log(`[node-socket-worker:${workerId}] ${message}`);
}

function sendFrame(frame) {
  if (!socket || socket.destroyed) {
    return;
  }
  writeFrame(socket, frame);
}

function writeFrame(targetSocket, frame) {
  targetSocket.write(`${JSON.stringify(frame)}\n`);
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
  receivedActionCount += 1;

  if (dispatchFaultMode === "exit-before-result" && receivedActionCount === 1) {
    log(`fault exit-before-result replyRef=${action?.replyRef ?? "<none>"}`);
    setTimeout(() => process.exit(2), 50);
    if (socket && !socket.destroyed) {
      socket.destroy();
    }
    return;
  }

  if (dispatchFaultMode === "late-result-after-lease-expiry" && receivedActionCount === 1) {
    const result = handler
      ? await handler(action)
      : {
          success: false,
          resultCode: "UNSUPPORTED_EVENT_CODE",
          result: {
            detail: `Unsupported eventCode: ${eventCode ?? "<missing>"}`,
          },
        };
    log(`fault late-result-after-lease-expiry replyRef=${action?.replyRef ?? "<none>"} delayMs=${resultDelayMs}`);
    if (socket && !socket.destroyed) {
      socket.destroy();
    }
    await sleep(resultDelayMs);
    await sendLateReplay(action, result);
    process.exit(0);
    return;
  }

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

function sendLateReplay(action, result) {
  return new Promise((resolve, reject) => {
    const replaySocket = net.createConnection({ host: socketHost, port: socketPort }, () => {
      writeFrame(replaySocket, { type: "hello", workerId, workerGroupId });
      writeFrame(replaySocket, buildTaskResult(action, result));
      log(`fault late-result-after-lease-expiry submitted result replyRef=${action?.replyRef ?? "<none>"}`);
      replaySocket.end();
      setTimeout(resolve, 200);
    });
    replaySocket.on("error", reject);
    replaySocket.on("close", resolve);
  });
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

function intEnv(name, fallback) {
  const parsed = Number.parseInt(process.env[name] ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function sleep(delayMillis) {
  return new Promise((resolve) => setTimeout(resolve, delayMillis));
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
  sendFrame({ type: "hello", workerId, workerGroupId });
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
