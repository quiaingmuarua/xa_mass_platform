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
  const output = compactObject({
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
  });

  return JSON.stringify({
    messageId: taskFrame?.messageId,
    taskId: taskFrame?.taskId,
    success: Boolean(result?.success),
    detail: result?.detail ?? "completed by external node worker",
    errorCode: result?.errorCode ?? null,
    output,
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

async function handleStockQuoteFetch(frame) {
  const input = frame?.input ?? {};
  const requestId = stringValue(input.requestId);
  const symbol = stringValue(input.symbol)?.toUpperCase();
  const market = stringValue(input.market) ?? "UNKNOWN";
  const sourceUrl = resolveStockSourceUrl(frame, symbol);

  if (!requestId || !symbol) {
    return {
      success: false,
      detail: "requestId and symbol are required in TaskDispatchItem.input",
      errorCode: "INVALID_INPUT",
      output: {
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
      detail: response.ok ? "stock-quote-success" : `stock-quote-http-${response.status}`,
      errorCode: response.ok ? null : `HTTP_${response.status}`,
      output: {
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
      detail: error instanceof Error ? error.message : String(error),
      errorCode: "QUOTE_FETCH_ERROR",
      output: {
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

function resolveStockSourceUrl(frame, symbol) {
  const configured = stringValue(frame?.input?.sourceUrl)
    ?? stringValue(frame?.input?.quoteUrl)
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
