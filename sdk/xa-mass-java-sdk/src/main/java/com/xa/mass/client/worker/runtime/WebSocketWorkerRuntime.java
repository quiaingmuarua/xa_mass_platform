package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.handler.WorkerActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class WebSocketWorkerRuntime implements WorkerRuntime {
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int FRAME_FAILURE_PREVIEW_LIMIT = 512;

    private final String workerId;
    private final String workerGroupId;
    private final Duration connectTimeout;
    private final Duration reconnectBackoff;
    private final Duration maxReconnectBackoff;
    private final int maxReconnectAttempts;
    private final WorkerRuntimeListener listener;
    private final WorkerDispatchProcessor dispatchProcessor;
    private final WorkerRuntimeReporter reporter;
    private final WebSocketWorkerProtocolDriver protocolDriver;
    private final ScheduledExecutorService executor;
    private final LinkedBlockingDeque<QueuedWebSocketResultFrame> outboundResults;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger consecutiveConnectionFailures = new AtomicInteger();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicReference<QueuedResultTermination> queuedResultTermination = new AtomicReference<>();

    private WebSocketWorkerRuntime(Builder builder) {
        WorkerRuntimeContext context = new WorkerRuntimeContext(
                builder.workerClient,
                builder.definition,
                new WorkerRuntimeOptions(builder.listener, builder.executor),
                "xa-mass-websocket-worker-");
        this.workerId = context.workerId();
        this.workerGroupId = context.workerGroupId();
        this.connectTimeout = builder.connectTimeout;
        this.reconnectBackoff = builder.reconnectBackoff;
        this.maxReconnectBackoff = builder.maxReconnectBackoff;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.listener = context.listener();
        this.dispatchProcessor = context.dispatchProcessor();
        this.reporter = context.reporter();
        this.protocolDriver = new WebSocketWorkerProtocolDriver(
                workerId,
                workerGroupId,
                builder.endpoint,
                connectTimeout,
                builder.httpClient,
                builder.objectMapper,
                builder.webSocketConnector);
        this.executor = context.executor();
        this.outboundResults = new LinkedBlockingDeque<>(builder.outboundQueueCapacity);
    }

    public static Builder builder(WorkerClient workerClient, WorkerRuntimeDefinition definition) {
        return new Builder(workerClient, definition);
    }

    @Override
    public WebSocketWorkerRuntime start() {
        WorkerRuntimeStartupStep lastSuccessful = null;
        try {
            running.set(true);
            webSocket.set(connectWebSocket());
            lastSuccessful = WorkerRuntimeStartupStep.CONNECT_WEBSOCKET;

            executor.execute(this::resultSenderLoop);
            lastSuccessful = WorkerRuntimeStartupStep.START_RESULT_SENDER;
            return this;
        } catch (Throwable failure) {
            running.set(false);
            executor.shutdownNow();
            WorkerRuntimeStartupStep failedStep = nextWebSocketStep(lastSuccessful);
            WorkerRuntimeFailureEvent startupFailure =
                    WorkerRuntimeFailureEvent.startup(workerId, failedStep, lastSuccessful, unwrap(failure));
            listener.onFailure(startupFailure);
            throw new WorkerRuntimeStartupException(startupFailure, unwrap(failure));
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String workerId() {
        return workerId;
    }

    @Override
    public String workerGroupId() {
        return workerGroupId;
    }

    @Override
    public String transportHint() {
        return "realtime";
    }

    @Override
    public WorkerRuntimeReporter reporter() {
        return reporter;
    }

    public int pendingResults() {
        return outboundResults.size();
    }

    Duration connectTimeout() {
        return protocolDriver.connectTimeout();
    }

    HttpClient httpClient() {
        return protocolDriver.httpClient();
    }

    ObjectMapper objectMapper() {
        return protocolDriver.objectMapper();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        closing.set(true);
        queuedResultTermination.compareAndSet(null, new QueuedResultTermination(
                "SESSION_CLOSED",
                new IllegalStateException("websocket session closed before queued result was sent")));
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "websocket-session-close");
            } catch (Throwable failure) {
                listener.onFailure(WorkerRuntimeFailureEvent.shutdown(workerId, failure));
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(100L, connectTimeout.toMillis() + 500L),
                    TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        QueuedResultTermination termination = queuedResultTermination.get();
        abandonQueuedResults(termination.reason(), termination.cause());
    }

    private WebSocket connectWebSocket() throws ExecutionException, InterruptedException, TimeoutException {
        long generation = connectionGeneration.incrementAndGet();
        WebSocket socket = protocolDriver.connect(new SessionWebSocketListener(generation));
        consecutiveConnectionFailures.set(0);
        return socket;
    }

    private void reconnectOnce() {
        if (!running.get() || closing.get()) {
            return;
        }
        try {
            WebSocket socket = connectWebSocket();
            webSocket.set(socket);
            listener.onConnectionRecovered(workerId);
        } catch (Throwable failure) {
            int failures = consecutiveConnectionFailures.incrementAndGet();
            Throwable cause = unwrap(failure);
            listener.onFailure(WorkerRuntimeFailureEvent.connection(workerId, failures, cause));
            if (reconnectAttemptsExhausted(failures)) {
                stopAfterReconnectExhausted(cause);
                return;
            }
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || closing.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        int failures = Math.max(1, consecutiveConnectionFailures.get());
        executor.schedule(() -> {
            reconnectScheduled.set(false);
            reconnectOnce();
        }, connectionBackoff(failures).toMillis(), TimeUnit.MILLISECONDS);
    }

    private void resultSenderLoop() {
        while (running.get() || !outboundResults.isEmpty()) {
            QueuedWebSocketResultFrame outbound = null;
            try {
                outbound = outboundResults.poll(200L, TimeUnit.MILLISECONDS);
                if (outbound == null) {
                    continue;
                }
                WebSocket socket = webSocket.get();
                if (socket == null) {
                    if (!running.get() || closing.get()) {
                        QueuedResultTermination termination = queuedResultTermination();
                        abandonResult(outbound, termination.reason(), termination.cause());
                        continue;
                    }
                    requeueOrAbandon(outbound, new IllegalStateException("websocket is unavailable"));
                    sleep(Duration.ofMillis(100L));
                    continue;
                }
                socket.sendText(outbound.resultFrame(), true)
                        .get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                if (outbound != null) {
                    listener.onFailure(WorkerRuntimeFailureEvent.submit(
                            workerId,
                            outbound.replyRef(),
                            null,
                            cause));
                    requeueOrAbandon(outbound, cause);
                }
                webSocket.set(null);
                int failures = consecutiveConnectionFailures.incrementAndGet();
                listener.onFailure(WorkerRuntimeFailureEvent.connection(workerId, failures, cause));
                if (reconnectAttemptsExhausted(failures)) {
                    stopAfterReconnectExhausted(cause);
                    return;
                }
                scheduleReconnect();
                sleep(connectionBackoff(failures));
            }
        }
    }

    private void handleFrame(String frame) {
        WorkerAction item;
        try {
            item = protocolDriver.decodeDispatchFrame(frame);
        } catch (Throwable failure) {
            listener.onFailure(frameFailure(frame, failure));
            return;
        }
        if (item == null) {
            return;
        }
        WorkerDispatchProcessor.ProcessedDispatch processed = dispatchProcessor.process(item);
        enqueueResult(processed.replyRef(), processed.action(), processed.result());
    }

    private void enqueueResult(String replyRef,
                               WorkerAction action,
                               WorkerActionResult result) {
        try {
            String frame = protocolDriver.encodeResultFrame(replyRef, result);
            if (!outboundResults.offer(new QueuedWebSocketResultFrame(replyRef, frame))) {
                IllegalStateException failure = new IllegalStateException("websocket result queue is full");
                listener.onFailure(WorkerRuntimeFailureEvent.queuedResultDropped(
                        workerId,
                        replyRef,
                        "QUEUE_FULL",
                        failure));
                throw failure;
            }
        } catch (Throwable failure) {
            listener.onFailure(WorkerRuntimeFailureEvent.submit(workerId, replyRef, action, failure));
        }
    }

    private void requeueOrAbandon(QueuedWebSocketResultFrame outbound, Throwable cause) {
        if (!outboundResults.offerFirst(outbound)) {
            abandonResult(outbound,
                    "REQUEUE_FAILED",
                    cause);
        }
    }

    private WorkerRuntimeFailureEvent frameFailure(String frame, Throwable cause) {
        String safeFrame = frame == null ? "" : frame;
        String preview = safeFrame.length() <= FRAME_FAILURE_PREVIEW_LIMIT
                ? safeFrame
                : safeFrame.substring(0, FRAME_FAILURE_PREVIEW_LIMIT);
        return WorkerRuntimeFailureEvent.frame(workerId, preview, safeFrame.length(), cause);
    }

    Duration connectionBackoff(int consecutiveFailures) {
        int exponent = Math.max(0, Math.min(30, consecutiveFailures - 1));
        long multiplier = 1L << exponent;
        long millis = Math.min(maxReconnectBackoff.toMillis(), reconnectBackoff.toMillis() * multiplier);
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private boolean reconnectAttemptsExhausted(int failures) {
        return failures >= maxReconnectAttempts;
    }

    private void stopAfterReconnectExhausted(Throwable cause) {
        queuedResultTermination.compareAndSet(null, new QueuedResultTermination(
                "RECONNECT_EXHAUSTED", cause));
        running.set(false);
        closing.set(true);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        abandonQueuedResults("RECONNECT_EXHAUSTED", cause);
    }

    private QueuedResultTermination queuedResultTermination() {
        QueuedResultTermination termination = queuedResultTermination.get();
        if (termination != null) {
            return termination;
        }
        return new QueuedResultTermination(
                "SESSION_CLOSED",
                new IllegalStateException("websocket session closed before queued result was sent"));
    }

    private void abandonQueuedResults(String reason, Throwable cause) {
        QueuedWebSocketResultFrame outbound;
        while ((outbound = outboundResults.poll()) != null) {
            abandonResult(outbound, reason, cause);
        }
    }

    private void abandonResult(QueuedWebSocketResultFrame outbound, String reason, Throwable cause) {
        listener.onFailure(WorkerRuntimeFailureEvent.queuedResultAbandoned(
                workerId,
                outbound.replyRef(),
                reason,
                cause));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private static WorkerRuntimeStartupStep nextWebSocketStep(WorkerRuntimeStartupStep lastSuccessful) {
        if (lastSuccessful == null) {
            return WorkerRuntimeStartupStep.CONNECT_WEBSOCKET;
        }
        return switch (lastSuccessful) {
            case CONNECT_WEBSOCKET -> WorkerRuntimeStartupStep.START_RESULT_SENDER;
            default -> lastSuccessful;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof ExecutionException executionException && executionException.getCause() != null) {
            return executionException.getCause();
        }
        return failure;
    }

    public static final class Builder {
        private final WorkerClient workerClient;
        private final WorkerRuntimeDefinition definition;
        private URI endpoint;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration reconnectBackoff = Duration.ofMillis(500);
        private Duration maxReconnectBackoff = Duration.ofSeconds(10);
        private int maxReconnectAttempts = 10;
        private int outboundQueueCapacity = 1024;
        private WorkerRuntimeListener listener = WorkerRuntimeListener.NOOP;
        private HttpClient httpClient;
        private ObjectMapper objectMapper = DEFAULT_OBJECT_MAPPER;
        private WebSocketConnector webSocketConnector;
        private ScheduledExecutorService executor;

        private Builder(WorkerClient workerClient, WorkerRuntimeDefinition definition) {
            this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
            this.definition = Objects.requireNonNull(definition, "definition is required");
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder reconnectBackoff(Duration reconnectBackoff) {
            this.reconnectBackoff = requirePositive(reconnectBackoff, "reconnectBackoff");
            return this;
        }

        public Builder maxReconnectBackoff(Duration maxReconnectBackoff) {
            this.maxReconnectBackoff = requirePositive(maxReconnectBackoff, "maxReconnectBackoff");
            return this;
        }

        public Builder maxReconnectAttempts(int maxReconnectAttempts) {
            if (maxReconnectAttempts <= 0) {
                throw new IllegalArgumentException("maxReconnectAttempts must be positive");
            }
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder outboundQueueCapacity(int outboundQueueCapacity) {
            if (outboundQueueCapacity <= 0) {
                throw new IllegalArgumentException("outboundQueueCapacity must be positive");
            }
            this.outboundQueueCapacity = outboundQueueCapacity;
            return this;
        }

        public Builder listener(WorkerRuntimeListener listener) {
            this.listener = listener == null ? WorkerRuntimeListener.NOOP : listener;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
            return this;
        }

        Builder webSocketConnector(WebSocketConnector webSocketConnector) {
            this.webSocketConnector = Objects.requireNonNull(webSocketConnector, "webSocketConnector is required");
            return this;
        }

        Builder executor(ScheduledExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor is required");
            return this;
        }

        public WebSocketWorkerRuntime start() {
            return new WebSocketWorkerRuntime(this).start();
        }

        public WebSocketWorkerRuntime buildUnstarted() {
            return new WebSocketWorkerRuntime(this);
        }

        private static Duration requirePositive(Duration value, String fieldName) {
            Objects.requireNonNull(value, fieldName + " is required");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(fieldName + " must be positive");
            }
            return value;
        }
    }

    private final class SessionWebSocketListener implements WebSocket.Listener {
        private final long generation;
        private final StringBuilder partialText = new StringBuilder();

        private SessionWebSocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partialText.append(data);
            if (last) {
                String frame = partialText.toString();
                partialText.setLength(0);
                handleFrame(frame);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket socket, int statusCode, String reason) {
            clearSocketIfCurrent(generation, socket);
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            clearSocketIfCurrent(generation, socket);
            int failures = consecutiveConnectionFailures.incrementAndGet();
            listener.onFailure(WorkerRuntimeFailureEvent.connection(workerId, failures, error));
            scheduleReconnect();
        }
    }

    private void clearSocketIfCurrent(long generation, WebSocket socket) {
        if (connectionGeneration.get() == generation) {
            webSocket.compareAndSet(socket, null);
        }
    }

    private record QueuedWebSocketResultFrame(String replyRef, String resultFrame) {
    }

    private record QueuedResultTermination(String reason, Throwable cause) {
    }

}
