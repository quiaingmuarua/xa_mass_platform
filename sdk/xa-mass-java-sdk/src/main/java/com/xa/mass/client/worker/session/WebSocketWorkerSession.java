package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.worker.AdapterNodeSpec;
import com.xa.mass.client.worker.NodeGroupBindingSpec;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerDispatchItem;
import com.xa.mass.client.worker.WorkerRegistrationResult;
import com.xa.mass.client.worker.WorkerSpec;
import com.xa.mass.client.worker.handler.DispatchContext;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerEventHandlerRuntime;
import com.xa.mass.client.worker.handler.WorkerEventHandlers;
import com.xa.mass.client.worker.handler.WorkerEventInvocation;
import com.xa.mass.client.worker.handler.WorkerResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class WebSocketWorkerSession implements AutoCloseable {
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int FRAME_FAILURE_PREVIEW_LIMIT = 512;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WorkerClient workerClient;
    private final String workerId;
    private final String workerGroupId;
    private final String adapterNodeId;
    private final String adapterType;
    private final String adapterVersion;
    private final String endpointId;
    private final String pluginVersion;
    private final String deploymentVersion;
    private final Map<String, String> attributes;
    private final URI endpoint;
    private final Duration connectTimeout;
    private final Duration reconnectBackoff;
    private final Duration maxReconnectBackoff;
    private final int maxReconnectAttempts;
    private final WorkerSessionListener listener;
    private final WorkerEventHandlers eventHandlers;
    private final WorkerEventHandlerRuntime handlerRuntime;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final WebSocketConnector webSocketConnector;
    private final ScheduledExecutorService executor;
    private final LinkedBlockingDeque<OutboundResult> outboundResults;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger consecutiveConnectionFailures = new AtomicInteger();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final AtomicReference<QueuedResultTermination> queuedResultTermination = new AtomicReference<>();

    private WebSocketWorkerSession(Builder builder) {
        this.workerClient = builder.workerClient;
        this.workerId = requireText(builder.workerId, "workerId");
        this.workerGroupId = requireText(builder.workerGroupId, "workerGroupId");
        this.adapterNodeId = optionalText(builder.adapterNodeId);
        this.adapterType = firstNonBlank(builder.adapterType, "websocket");
        this.adapterVersion = builder.adapterVersion;
        this.endpointId = firstNonBlank(builder.endpointId, adapterNodeId != null ? adapterNodeId : workerId);
        this.pluginVersion = builder.pluginVersion;
        this.deploymentVersion = builder.deploymentVersion;
        this.attributes = Map.copyOf(builder.attributes);
        this.endpoint = Objects.requireNonNull(builder.endpoint, "endpoint is required");
        this.connectTimeout = builder.connectTimeout;
        this.reconnectBackoff = builder.reconnectBackoff;
        this.maxReconnectBackoff = builder.maxReconnectBackoff;
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.listener = builder.listener;
        this.eventHandlers = builder.eventHandlers.build();
        this.handlerRuntime = new WorkerEventHandlerRuntime(eventHandlers);
        this.objectMapper = builder.objectMapper;
        this.httpClient = builder.httpClient;
        this.webSocketConnector = builder.webSocketConnector == null
                ? new DefaultWebSocketConnector(builder.httpClient, connectTimeout)
                : builder.webSocketConnector;
        this.executor = builder.executor == null
                ? Executors.newScheduledThreadPool(2, new SessionThreadFactory(workerId))
                : builder.executor;
        this.outboundResults = new LinkedBlockingDeque<>(builder.outboundQueueCapacity);
    }

    public static Builder builder(WorkerClient workerClient) {
        return new Builder(workerClient);
    }

    public WebSocketWorkerSession start() {
        WorkerSessionStartupStep lastSuccessful = null;
        try {
            if (adapterNodeId != null) {
                workerClient.registerAdapterNode(AdapterNodeSpec.builder()
                        .adapterNodeId(adapterNodeId)
                        .adapterType(adapterType)
                        .adapterVersion(adapterVersion)
                        .endpointId(endpointId)
                        .attributes(attributes)
                        .build());
                lastSuccessful = WorkerSessionStartupStep.REGISTER_ADAPTER_NODE;

                workerClient.bindNodeGroup(NodeGroupBindingSpec.builder()
                        .adapterNodeId(adapterNodeId)
                        .workerGroupId(workerGroupId)
                        .pluginVersion(pluginVersion)
                        .deploymentVersion(deploymentVersion)
                        .attributes(attributes)
                        .build());
                lastSuccessful = WorkerSessionStartupStep.BIND_NODE_GROUP;
            }

            WorkerRegistrationResult registration = workerClient.registerWorker(WorkerSpec.builder()
                    .workerId(workerId)
                    .workerGroupId(workerGroupId)
                    .realtime()
                    .attributes(attributes)
                    .build());
            lastSuccessful = WorkerSessionStartupStep.REGISTER_WORKER;

            running.set(true);
            webSocket.set(connectWebSocket());
            lastSuccessful = WorkerSessionStartupStep.CONNECT_WEBSOCKET;

            executor.execute(this::resultSenderLoop);
            lastSuccessful = WorkerSessionStartupStep.START_RESULT_SENDER;
            return this;
        } catch (Throwable failure) {
            running.set(false);
            executor.shutdownNow();
            WorkerSessionStartupStep failedStep = nextWebSocketStep(lastSuccessful, adapterNodeId != null);
            WorkerSessionStartupFailure startupFailure =
                    new WorkerSessionStartupFailure(workerId, failedStep, lastSuccessful, unwrap(failure));
            listener.onStartupFailure(startupFailure);
            throw new WorkerSessionStartupException(startupFailure);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int pendingResults() {
        return outboundResults.size();
    }

    Duration connectTimeout() {
        return connectTimeout;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        closing.set(true);
        queuedResultTermination.compareAndSet(null, new QueuedResultTermination(
                WorkerSessionQueuedResultFailure.Reason.SESSION_CLOSED,
                new IllegalStateException("websocket session closed before queued result was sent")));
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "websocket-session-close");
            } catch (Throwable failure) {
                listener.onShutdownFailure(workerId, failure);
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
        URI connectUri = connectUri();
        WebSocket socket = webSocketConnector.connect(connectUri, new SessionWebSocketListener(generation))
                .get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
            listener.onConnectionFailure(new WorkerSessionConnectionFailure(workerId, failures, cause));
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
            OutboundResult outbound = null;
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
                    listener.onSubmitFailure(new WorkerSessionDispatchFailure(outbound.dispatch(), cause));
                    requeueOrAbandon(outbound, cause);
                }
                webSocket.set(null);
                int failures = consecutiveConnectionFailures.incrementAndGet();
                listener.onConnectionFailure(new WorkerSessionConnectionFailure(workerId, failures, cause));
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
        WorkerDispatchItem item;
        try {
            item = decodeDispatch(frame);
        } catch (Throwable failure) {
            listener.onFrameFailure(frameFailure(frame, failure));
            return;
        }
        if (item == null) {
            return;
        }
        DispatchContext dispatch = DispatchContext.from(item);
        WorkerEventInvocation invocation = handlerRuntime.invoke(dispatch);
        if (invocation.handlerFailed()) {
            listener.onHandlerFailure(new WorkerSessionDispatchFailure(dispatch, invocation.failure()));
        }
        enqueueResult(dispatch, invocation.result());
    }

    private void enqueueResult(DispatchContext dispatch, WorkerResult result) {
        try {
            String frame = encodeResult(dispatch, result);
            if (!outboundResults.offer(new OutboundResult(dispatch, frame))) {
                IllegalStateException failure = new IllegalStateException("websocket result queue is full");
                WorkerSessionQueuedResultFailure queuedFailure = new WorkerSessionQueuedResultFailure(
                        workerId,
                        dispatch,
                        WorkerSessionQueuedResultFailure.Reason.QUEUE_FULL,
                        failure);
                listener.onQueuedResultDropped(queuedFailure);
                throw failure;
            }
        } catch (Throwable failure) {
            listener.onSubmitFailure(new WorkerSessionDispatchFailure(dispatch, failure));
        }
    }

    private void requeueOrAbandon(OutboundResult outbound, Throwable cause) {
        if (!outboundResults.offerFirst(outbound)) {
            abandonResult(outbound,
                    WorkerSessionQueuedResultFailure.Reason.REQUEUE_FAILED,
                    cause);
        }
    }

    private WorkerSessionFrameFailure frameFailure(String frame, Throwable cause) {
        String safeFrame = frame == null ? "" : frame;
        String preview = safeFrame.length() <= FRAME_FAILURE_PREVIEW_LIMIT
                ? safeFrame
                : safeFrame.substring(0, FRAME_FAILURE_PREVIEW_LIMIT);
        return new WorkerSessionFrameFailure(workerId, preview, safeFrame.length(), cause);
    }

    private WorkerDispatchItem decodeDispatch(String frame) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(frame);
        String taskId = text(root, "taskId");
        String messageId = text(root, "messageId");
        if (taskId == null || messageId == null) {
            return null;
        }
        if (root.has("success")) {
            return null;
        }
        return new WorkerDispatchItem(
                taskId,
                messageId,
                text(root, "eventCode"),
                text(root, "taskName"),
                text(root, "project"),
                text(root, "userId"),
                root.path("retryCount").asInt(0),
                firstNonBlank(text(root, "workerId"), workerId),
                text(root, "batchId"),
                objectMap(root.get("input")),
                objectMap(root.get("sharedConfig"))
        );
    }

    private String encodeResult(DispatchContext dispatch, WorkerResult result) throws JsonProcessingException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("messageId", dispatch.messageId());
        frame.put("taskId", dispatch.taskId());
        frame.put("success", result.success());
        frame.put("detail", result.detail());
        frame.put("errorCode", result.errorCode());
        frame.put("output", result.output());
        return objectMapper.writeValueAsString(frame);
    }

    private URI connectUri() {
        String raw = endpoint.toString();
        String separator = endpoint.getRawQuery() == null ? "?" : "&";
        return URI.create(raw
                + separator
                + "workerId=" + encodeQuery(workerId)
                + "&workerGroupId=" + encodeQuery(workerGroupId));
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
                WorkerSessionQueuedResultFailure.Reason.RECONNECT_EXHAUSTED, cause));
        running.set(false);
        closing.set(true);
        WebSocket socket = webSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        abandonQueuedResults(WorkerSessionQueuedResultFailure.Reason.RECONNECT_EXHAUSTED, cause);
    }

    private QueuedResultTermination queuedResultTermination() {
        QueuedResultTermination termination = queuedResultTermination.get();
        if (termination != null) {
            return termination;
        }
        return new QueuedResultTermination(
                WorkerSessionQueuedResultFailure.Reason.SESSION_CLOSED,
                new IllegalStateException("websocket session closed before queued result was sent"));
    }

    private void abandonQueuedResults(WorkerSessionQueuedResultFailure.Reason reason, Throwable cause) {
        OutboundResult outbound;
        while ((outbound = outboundResults.poll()) != null) {
            abandonResult(outbound, reason, cause);
        }
    }

    private void abandonResult(OutboundResult outbound, WorkerSessionQueuedResultFailure.Reason reason,
                               Throwable cause) {
        listener.onQueuedResultAbandoned(new WorkerSessionQueuedResultFailure(
                workerId,
                outbound.dispatch(),
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

    private Map<String, Object> objectMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static WorkerSessionStartupStep nextWebSocketStep(WorkerSessionStartupStep lastSuccessful,
                                                              boolean topologyBootstrapEnabled) {
        if (lastSuccessful == null) {
            return topologyBootstrapEnabled
                    ? WorkerSessionStartupStep.REGISTER_ADAPTER_NODE
                    : WorkerSessionStartupStep.REGISTER_WORKER;
        }
        return switch (lastSuccessful) {
            case REGISTER_ADAPTER_NODE -> WorkerSessionStartupStep.BIND_NODE_GROUP;
            case BIND_NODE_GROUP -> WorkerSessionStartupStep.REGISTER_WORKER;
            case REGISTER_WORKER -> WorkerSessionStartupStep.CONNECT_WEBSOCKET;
            case CONNECT_WEBSOCKET -> WorkerSessionStartupStep.START_RESULT_SENDER;
            default -> lastSuccessful;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof ExecutionException executionException && executionException.getCause() != null) {
            return executionException.getCause();
        }
        return failure;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private final WorkerClient workerClient;
        private String workerId;
        private String workerGroupId;
        private String adapterNodeId;
        private String adapterType = "websocket";
        private String adapterVersion;
        private String endpointId;
        private String pluginVersion;
        private String deploymentVersion;
        private Map<String, String> attributes = new LinkedHashMap<>();
        private URI endpoint;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration reconnectBackoff = Duration.ofMillis(500);
        private Duration maxReconnectBackoff = Duration.ofSeconds(10);
        private int maxReconnectAttempts = 10;
        private int outboundQueueCapacity = 1024;
        private WorkerEventHandlers.Builder eventHandlers = WorkerEventHandlers.builder();
        private WorkerSessionListener listener = WorkerSessionListener.NOOP;
        private HttpClient httpClient;
        private ObjectMapper objectMapper = DEFAULT_OBJECT_MAPPER;
        private WebSocketConnector webSocketConnector;
        private ScheduledExecutorService executor;

        private Builder(WorkerClient workerClient) {
            this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
            return this;
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder adapterType(String adapterType) {
            this.adapterType = adapterType;
            return this;
        }

        public Builder adapterVersion(String adapterVersion) {
            this.adapterVersion = adapterVersion;
            return this;
        }

        public Builder endpointId(String endpointId) {
            this.endpointId = endpointId;
            return this;
        }

        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        public Builder deploymentVersion(String deploymentVersion) {
            this.deploymentVersion = deploymentVersion;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder event(String eventCode, WorkerDispatchHandler handler) {
            Objects.requireNonNull(handler, "handler is required");
            return eventHandler(eventCode, handler::handle);
        }

        public Builder eventHandler(String eventCode, WorkerEventHandler handler) {
            this.eventHandlers.event(requireText(eventCode, "eventCode"),
                    Objects.requireNonNull(handler, "handler is required"));
            return this;
        }

        public Builder eventHandlers(WorkerEventHandlers eventHandlers) {
            this.eventHandlers.events(eventHandlers);
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

        public Builder listener(WorkerSessionListener listener) {
            this.listener = listener == null ? WorkerSessionListener.NOOP : listener;
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

        public WebSocketWorkerSession start() {
            return new WebSocketWorkerSession(this).start();
        }

        public WebSocketWorkerSession buildUnstarted() {
            return new WebSocketWorkerSession(this);
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
            listener.onConnectionFailure(new WorkerSessionConnectionFailure(workerId, failures, error));
            scheduleReconnect();
        }
    }

    private void clearSocketIfCurrent(long generation, WebSocket socket) {
        if (connectionGeneration.get() == generation) {
            webSocket.compareAndSet(socket, null);
        }
    }

    private record OutboundResult(DispatchContext dispatch, String resultFrame) {
    }

    private record QueuedResultTermination(WorkerSessionQueuedResultFailure.Reason reason, Throwable cause) {
    }

    private static final class DefaultWebSocketConnector implements WebSocketConnector {
        private final HttpClient httpClient;
        private final Duration connectTimeout;

        private DefaultWebSocketConnector(HttpClient httpClient, Duration connectTimeout) {
            this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
            this.connectTimeout = connectTimeout;
        }

        @Override
        public CompletableFuture<WebSocket> connect(URI endpoint, WebSocket.Listener listener) {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(endpoint, listener);
        }
    }

    private static final class SessionThreadFactory implements ThreadFactory {
        private final String workerId;
        private final AtomicInteger counter = new AtomicInteger();

        private SessionThreadFactory(String workerId) {
            this.workerId = workerId;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "xa-mass-websocket-worker-" + workerId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}

@FunctionalInterface
interface WebSocketConnector {
    CompletableFuture<WebSocket> connect(URI endpoint, WebSocket.Listener listener);
}
