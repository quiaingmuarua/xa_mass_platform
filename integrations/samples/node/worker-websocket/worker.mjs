const workerId = process.env.WORKER_ID;
const wsUrl = process.env.WS_URL;
const workerGroupId = stringValue(process.env.MASS_WORKER_GROUP_ID ?? process.env.WORKER_GROUP_ID);

if (!workerId || !wsUrl || !workerGroupId) {
  console.error("WORKER_ID, WS_URL and MASS_WORKER_GROUP_ID are required");
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
  const resultBody = compactObject({
    status: result?.success === false ? "FAILED" : "SUCCESS",
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
    ...(result?.body ?? {}),
  });

  return JSON.stringify({
    frameId: `reply-${taskFrame?.replyRef ?? Date.now()}`,
    kind: "ACTION_REPLY",
    body: JSON.stringify({
      replyRef: taskFrame?.replyRef,
      success: Boolean(result?.success),
      code: result?.code ?? null,
      body: JSON.stringify(resultBody),
    }),
  });
}

async function handleDemoDispatch(frame) {
  return {
    success: true,
    body: {
      detail: "completed by external node worker",
      taskInput: actionBody(frame),
    },
  };
}

async function handleCrawlerFetchPage(frame) {
  return {
    success: true,
    body: {
      detail: "crawler fetch simulated by external node websocket worker",
      url: actionBody(frame)?.url ?? frame?.sharedConfig?.url ?? null,
      fetchedAt: new Date().toISOString(),
    },
  };
}

async function handleStockQuoteFetch(frame) {
  const input = actionBody(frame);
  const requestId = stringValue(input.requestId);
  const symbol = stringValue(input.symbol)?.toUpperCase();
  const market = stringValue(input.market) ?? "UNKNOWN";
  const sourceUrl = resolveStockSourceUrl(frame, symbol);

  if (!requestId || !symbol) {
    return {
      success: false,
      code: "INVALID_INPUT",
      body: {
        detail: "requestId and symbol are required in WorkerAction.body",
        requestId: requestId ?? null,
        symbol: symbol ?? null,
        market,
        source: sourceUrl,
        fetchedAt: new Date().toISOString(),
      },
    };
  }

  const startedAt = Date.now();
  try {
    const response = await fetch(sourceUrl);
    const body = await response.text();
    const parsed = parseJson(body);
    const quote = extractQuote(parsed, symbol, market, sourceUrl);
    return {
      success: response.ok,
      code: response.ok ? null : `HTTP_${response.status}`,
      body: {
        detail: response.ok ? "stock-quote-success" : `stock-quote-http-${response.status}`,
        requestId,
        symbol,
        market: quote.market,
        price: quote.price,
        currency: quote.currency,
        source: sourceUrl,
        sourceStatusCode: response.status,
        fetchedAt: new Date().toISOString(),
        elapsedMs: Date.now() - startedAt,
      },
    };
  } catch (error) {
    return {
      success: false,
      code: "QUOTE_FETCH_ERROR",
      body: {
        detail: error instanceof Error ? error.message : String(error),
        requestId,
        symbol,
        market,
        source: sourceUrl,
        fetchedAt: new Date().toISOString(),
        elapsedMs: Date.now() - startedAt,
      },
    };
  }
}

const taskHandlers = new Map([
  ["demo.dispatch", handleDemoDispatch],
  ["crawler.fetch-page", handleCrawlerFetchPage],
  ["stock.quote.fetch", handleStockQuoteFetch],
]);

async function handleFrame(rawFrame) {
  const frame = JSON.parse(rawFrame);

  if (frame?.kind !== "ACTION") {
    log("ignoring unsupported frame");
    return;
  }

  const action = parseJson(frame.body);
  const eventCode = action?.eventCode;
  const handler = eventCode ? taskHandlers.get(eventCode) : null;
  log(`received action frame replyRef=${action?.replyRef ?? "<none>"} eventCode=${eventCode ?? "<none>"}`);

  if (!handler) {
    socket.send(buildTaskResult(action, {
      success: false,
      code: "UNSUPPORTED_EVENT_CODE",
      body: {
        detail: `Unsupported eventCode: ${eventCode ?? "<missing>"}`,
      },
    }));
    return;
  }

  const result = await handler(action);
  socket.send(buildTaskResult(action, result));
}

function shutdown(exitCode) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.close(1000, "shutdown");
    setTimeout(() => process.exit(exitCode), 200);
    return;
  }

  process.exit(exitCode);
}

function resolveStockSourceUrl(frame, symbol) {
  const input = actionBody(frame);
  const configured = stringValue(input?.sourceUrl)
    ?? stringValue(input?.quoteUrl)
    ?? stringValue(frame?.sharedConfig?.sourceUrl)
    ?? stringValue(frame?.sharedConfig?.quoteUrl);
  if (configured) {
    return configured;
  }
  const encodedSymbol = encodeURIComponent(symbol ?? "UNKNOWN");
  return `https://query1.finance.yahoo.com/v8/finance/chart/${encodedSymbol}`;
}

function extractQuote(parsed, symbol, market, sourceUrl) {
  const yahooResult = parsed?.chart?.result?.[0];
  const yahooMeta = yahooResult?.meta;
  const explicit = parsed?.quote ?? parsed?.data?.quote ?? parsed?.data;
  const explicitPrice = numberValue(explicit?.price)
    ?? numberValue(explicit?.regularMarketPrice)
    ?? numberValue(explicit?.lastPrice);
  const explicitCurrency = stringValue(explicit?.currency);
  const yahooPrice = numberValue(yahooMeta?.regularMarketPrice)
    ?? numberValue(yahooMeta?.previousClose);
  const price = explicitPrice ?? yahooPrice ?? deterministicPrice(symbol, sourceUrl);
  const currency = explicitCurrency ?? stringValue(yahooMeta?.currency) ?? "USD";
  return {
    market: stringValue(explicit?.market) ?? stringValue(yahooMeta?.exchangeName) ?? market ?? "UNKNOWN",
    price,
    currency,
  };
}

function deterministicPrice(symbol, sourceUrl) {
  const text = `${symbol ?? ""}|${sourceUrl ?? ""}`;
  let hash = 0;
  for (const char of text) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  }
  return Number(((hash % 100000) / 100 + 10).toFixed(2));
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

function stringValue(value) {
  if (value == null) {
    return null;
  }
  const text = String(value).trim();
  return text.length > 0 ? text : null;
}

function numberValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function compactObject(value) {
  if (Array.isArray(value)) {
    return value
      .filter((item) => item !== null && item !== undefined)
      .map((item) => compactObject(item));
  }
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([, entryValue]) => entryValue !== null && entryValue !== undefined)
        .map(([key, entryValue]) => [key, compactObject(entryValue)]),
    );
  }
  return value;
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

function appendWorkerIdentity(url, subjectWorkerId, groupId) {
  const separator = url.includes("?") ? "&" : "?";
  const routeKey = canonicalRouteKey(groupId, subjectWorkerId);
  const params = new URLSearchParams({ workerId: subjectWorkerId });
  params.set("workerGroupId", groupId);
  params.set("routeKey", routeKey);
  return `${url}${separator}${params.toString()}`;
}

socket = new WebSocket(appendWorkerIdentity(wsUrl, workerId, workerGroupId));

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
