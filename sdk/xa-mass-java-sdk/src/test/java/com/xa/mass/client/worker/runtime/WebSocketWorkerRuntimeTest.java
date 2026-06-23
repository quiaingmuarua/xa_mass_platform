package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.WorkerSpec;
import com.xa.mass.client.worker.handler.WorkerActionHandler;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketWorkerRuntimeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void managedSessionRegistersRealtimeWorkerConnectsAndSendsResultFrames() throws Exception {
        List<String> observed = new ArrayList<>();
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<URI> connectedUri = new AtomicReference<>();
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(resultSent);
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            observed.add(method + " " + path);
            String body = readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("ws-worker-001", request.get("workerId").asText());
                assertFalse(request.has("adapterId"));
                assertEquals("realtime", request.get("transportHint").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"ws-worker-001","workerGroupId":"realtime-probe","transportHint":"realtime"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        MassPlatform mass = platform();
        WorkerRuntimeDefinition definition = realtimeDefinition(dispatch -> WorkerActionResult.success("""
                        {"eventCode":"%s","title":"%s","integrationProbe":"java-sdk-websocket-session"}
                        """.formatted(dispatch.eventCode(), OBJECT_MAPPER.readTree(dispatch.body()).get("title").asText()).trim()))
                .attribute("region", "sg")
                .build();
        mass.workers().registerWorker(WorkerSpec.realtime(definition));

        try (WebSocketWorkerRuntime session = mass.workerRuntimes().webSocket(definition)
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    connectedUri.set(uri);
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            assertTrue(session.isRunning());
            assertEquals("workerId=ws-worker-001&workerGroupId=realtime-probe", connectedUri.get().getRawQuery());

            listenerRef.get().onText(webSocket, actionFrame("corr-1", "probe.realtime.metadata",
                    "{\"title\":\"hello\"}"), true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(resultSent.await(2, TimeUnit.SECONDS), "result frame should be sent");
            JsonNode result = sentReply(webSocket.sentTexts().get(0));
            assertEquals("corr-1", result.get("replyRef").asText());
            assertTrue(result.get("success").asBoolean());
            JsonNode resultBody = OBJECT_MAPPER.readTree(result.get("body").asText());
            assertEquals("hello", resultBody.get("title").asText());
            assertEquals("java-sdk-websocket-session", resultBody.get("integrationProbe").asText());
            assertEquals(0, session.pendingResults());
        }

        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:online"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:heartbeat"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:offline"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:report-handler-evidence"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:report-runtime-evidence"));
        assertTrue(webSocket.isOutputClosed(), "close should send a best-effort WebSocket close frame");
    }

    @Test
    void webSocketBuilderInheritsPlatformConnectionClientAndMapperDefaults() {
        HttpClient platformHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(123))
                .build();
        ObjectMapper platformMapper = new ObjectMapper().findAndRegisterModules();
        MassPlatform platform = MassPlatform.builder()
                .baseUrl("http://localhost:8088")
                .apiKey("mass_sk_worker")
                .connectTimeout(Duration.ofMillis(321))
                .httpClient(platformHttpClient)
                .objectMapper(platformMapper)
                .build();

        WebSocketWorkerRuntime session = platform.workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .buildUnstarted();

        assertEquals(Duration.ofMillis(321), session.connectTimeout());
        assertSame(platformHttpClient, session.httpClient());
        assertSame(platformMapper, session.objectMapper());
    }

    @Test
    void explicitWebSocketBuilderOverridesWinOverPlatformDefaults() {
        HttpClient platformHttpClient = HttpClient.newHttpClient();
        ObjectMapper platformMapper = new ObjectMapper().findAndRegisterModules();
        HttpClient overrideHttpClient = HttpClient.newHttpClient();
        ObjectMapper overrideMapper = new ObjectMapper().findAndRegisterModules();
        MassPlatform platform = MassPlatform.builder()
                .baseUrl("http://localhost:8088")
                .apiKey("mass_sk_worker")
                .connectTimeout(Duration.ofMillis(321))
                .httpClient(platformHttpClient)
                .objectMapper(platformMapper)
                .build();

        WebSocketWorkerRuntime session = platform.workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofMillis(654))
                .httpClient(overrideHttpClient)
                .objectMapper(overrideMapper)
                .buildUnstarted();

        assertEquals(Duration.ofMillis(654), session.connectTimeout());
        assertSame(overrideHttpClient, session.httpClient());
        assertSame(overrideMapper, session.objectMapper());
    }

    @Test
    void reconnectBackoffReachesConfiguredMaximum() {
        WebSocketWorkerRuntime session = WebSocketWorkerRuntime.builder(dummyWorkerClient(), realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .reconnectBackoff(Duration.ofMillis(500))
                .maxReconnectBackoff(Duration.ofSeconds(10))
                .buildUnstarted();

        assertEquals(Duration.ofMillis(500), session.connectionBackoff(1));
        assertEquals(Duration.ofSeconds(8), session.connectionBackoff(5));
        assertEquals(Duration.ofSeconds(10), session.connectionBackoff(6));
        assertEquals(Duration.ofSeconds(10), session.connectionBackoff(20));
    }

    @Test
    void controlFrameWithoutTaskIdentityIsIgnored() throws Exception {
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(resultSent);
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, channelFrame("HEARTBEAT", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertFalse(resultSent.await(150, TimeUnit.MILLISECONDS), "control frame should not emit a result");
            assertTrue(webSocket.sentTexts().isEmpty());
        }
    }

    @Test
    void invalidFrameReportsFrameFailureWithoutConnectionFailure() throws Exception {
        CountDownLatch frameFailed = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> frameFailure = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> connectionFailure = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.FRAME) {
                            frameFailure.set(failure);
                            frameFailed.countDown();
                        }
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.CONNECTION) {
                            connectionFailure.set(failure);
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, "{not-json", true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(frameFailed.await(2, TimeUnit.SECONDS), "invalid frame should be reported");
            assertEquals("ws-worker-001", frameFailure.get().workerId());
            assertEquals(WorkerRuntimeFailureEvent.Kind.FRAME, frameFailure.get().kind());
            assertEquals("{not-json", frameFailure.get().context().get("framePreview"));
            assertEquals(Integer.toString("{not-json".length()), frameFailure.get().context().get("frameLength"));
            assertNotNull(frameFailure.get().errorType());
            assertEquals(null, connectionFailure.get(), "frame decode failure must not be connection failure");
            assertTrue(webSocket.sentTexts().isEmpty());
        }
    }

    @Test
    void invalidLongFrameReportsBoundedFramePreview() throws Exception {
        CountDownLatch frameFailed = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> frameFailure = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.FRAME) {
                            frameFailure.set(failure);
                            frameFailed.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            String frame = "x".repeat(700);
            listenerRef.get().onText(webSocket, frame, true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(frameFailed.await(2, TimeUnit.SECONDS), "invalid frame should be reported");
            assertEquals(512, frameFailure.get().context().get("framePreview").length());
            assertEquals(frame.substring(0, 512), frameFailure.get().context().get("framePreview"));
            assertEquals(Integer.toString(frame.length()), frameFailure.get().context().get("frameLength"));
            assertNotNull(frameFailure.get().errorType());
        }
    }

    @Test
    void unknownEventSendsStructuredFailureResult() throws Exception {
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(resultSent);
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, actionFrame("corr-2", "probe.unknown", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(resultSent.await(2, TimeUnit.SECONDS), "failure result frame should be sent");
            JsonNode result = sentReply(webSocket.sentTexts().get(0));
            assertFalse(result.get("success").asBoolean());
            assertEquals("NO_HANDLER", result.get("code").asText());
        }
    }

    @Test
    void closeAbandonsQueuedResultWhenSocketIsUnavailable() throws Exception {
        CountDownLatch abandoned = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> failureRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        WebSocketWorkerRuntime session = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofMillis(100))
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_ABANDONED) {
                            failureRef.set(failure);
                            abandoned.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start();
        try {
            listenerRef.get().onClose(webSocket, 1006, "test-disconnect").toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            listenerRef.get().onText(webSocket, actionFrame("corr-close", "probe.realtime.metadata", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            session.close();

            assertTrue(abandoned.await(2, TimeUnit.SECONDS), "close should abandon queued result");
            assertEquals("SESSION_CLOSED", failureRef.get().reason());
            assertEquals("corr-close", failureRef.get().replyRef());
        } finally {
            session.close();
        }
    }

    @Test
    void fullQueueDropsResultWithSpecificCallback() throws Exception {
        CountDownLatch dropped = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> failureRef = new AtomicReference<>();
        BlockingWebSocket webSocket = new BlockingWebSocket();
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .outboundQueueCapacity(1)
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_DROPPED) {
                            failureRef.set(failure);
                            dropped.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            for (int i = 1; i <= 3; i++) {
                listenerRef.get().onText(webSocket,
                        actionFrame("corr-" + i, "probe.realtime.metadata", "{}"),
                        true).toCompletableFuture().get(1, TimeUnit.SECONDS);
            }

            assertTrue(dropped.await(2, TimeUnit.SECONDS), "full queue should drop a result");
            assertEquals("QUEUE_FULL", failureRef.get().reason());
        }
    }

    @Test
    void sendFailureRequeueFailureIsReportedWhenQueueRefills() throws Exception {
        CountDownLatch abandoned = new CountDownLatch(1);
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> failureRef = new AtomicReference<>();
        CoordinatedFailingWebSocket webSocket = new CoordinatedFailingWebSocket(sendStarted, allowFailure);
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerRuntime ignored = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofSeconds(1))
                .outboundQueueCapacity(1)
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_ABANDONED) {
                            failureRef.set(failure);
                            abandoned.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, actionFrame("corr-requeue-1", "probe.realtime.metadata", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(sendStarted.await(2, TimeUnit.SECONDS), "first send should be in progress");

            listenerRef.get().onText(webSocket, actionFrame("corr-requeue-2", "probe.realtime.metadata", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            allowFailure.countDown();

            assertTrue(abandoned.await(2, TimeUnit.SECONDS), "failed requeue should abandon the original result");
            assertEquals("REQUEUE_FAILED", failureRef.get().reason());
            assertEquals("corr-requeue-1", failureRef.get().replyRef());
            assertNotNull(failureRef.get().errorType());
        }
    }

    @Test
    void reconnectExhaustionAbandonsQueuedResult() throws Exception {
        CountDownLatch abandoned = new CountDownLatch(1);
        AtomicInteger connectAttempts = new AtomicInteger();
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerRuntimeFailureEvent> failureRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        WebSocketWorkerRuntime session = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofMillis(100))
                .reconnectBackoff(Duration.ofMillis(10))
                .maxReconnectAttempts(1)
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onFailure(WorkerRuntimeFailureEvent failure) {
                        if (failure.kind() == WorkerRuntimeFailureEvent.Kind.QUEUED_RESULT_ABANDONED) {
                            failureRef.set(failure);
                            abandoned.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    if (connectAttempts.incrementAndGet() == 1) {
                        listenerRef.set(listener);
                        listener.onOpen(webSocket);
                        return CompletableFuture.completedFuture(webSocket);
                    }
                    return CompletableFuture.failedFuture(new IOException("connect failed"));
                })
                .start();
        try {
            listenerRef.get().onClose(webSocket, 1006, "test-disconnect").toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            listenerRef.get().onText(webSocket, actionFrame("corr-reconnect", "probe.realtime.metadata", "{}"), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(abandoned.await(2, TimeUnit.SECONDS), "reconnect exhaustion should abandon queued result");
            assertEquals("RECONNECT_EXHAUSTED", failureRef.get().reason());
            assertEquals("corr-reconnect", failureRef.get().replyRef());
            assertFalse(session.isRunning());
        } finally {
            session.close();
        }
    }

    @Test
    void successfulReconnectReportsConnectionRecovered() throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch recoveryResultSent = new CountDownLatch(1);
        AtomicInteger connectAttempts = new AtomicInteger();
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket firstSocket = new RecordingWebSocket(new CountDownLatch(1));
        RecordingWebSocket secondSocket = new RecordingWebSocket(recoveryResultSent);
        startRealtimeControlPlaneServer();

        WebSocketWorkerRuntime session = platform().workerRuntimes().webSocket(realtimeDefinition())
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .connectTimeout(Duration.ofMillis(100))
                .reconnectBackoff(Duration.ofMillis(10))
                .listener(new WorkerRuntimeListener() {
                    @Override
                    public void onConnectionRecovered(String workerId) {
                        if ("ws-worker-001".equals(workerId)) {
                            listenerRef.get().onText(secondSocket,
                                    actionFrame("corr-recovered", "probe.realtime.metadata", "{}"),
                                    true);
                            recovered.countDown();
                        }
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    int attempt = connectAttempts.incrementAndGet();
                    listenerRef.set(listener);
                    if (attempt == 1) {
                        listener.onOpen(firstSocket);
                        return CompletableFuture.completedFuture(firstSocket);
                    }
                    listener.onOpen(secondSocket);
                    return CompletableFuture.completedFuture(secondSocket);
                })
                .start();
        try {
            listenerRef.get().onClose(firstSocket, 1006, "test-disconnect").toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);

            assertTrue(recovered.await(2, TimeUnit.SECONDS), "successful reconnect should report recovery");
            assertTrue(recoveryResultSent.await(2, TimeUnit.SECONDS),
                    "recovery callback should see the live replacement socket");
            JsonNode result = sentReply(secondSocket.sentTexts().get(0));
            assertEquals("corr-recovered", result.get("replyRef").asText());
            assertEquals(2, connectAttempts.get());
            assertTrue(session.isRunning());
        } finally {
            session.close();
        }
    }

    private MassPlatform platform() {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("mass_sk_worker")
                .build();
    }

    private static String actionFrame(String replyRef, String eventCode, String body) {
        ObjectNode action = OBJECT_MAPPER.createObjectNode();
        action.put("actionId", "action-" + replyRef);
        action.put("replyRef", replyRef);
        action.put("eventCode", eventCode);
        action.put("body", body);
        action.set("sharedConfig", OBJECT_MAPPER.createObjectNode());
        return channelFrame("ACTION", writeJson(action));
    }

    private static String channelFrame(String kind, String body) {
        ObjectNode frame = OBJECT_MAPPER.createObjectNode();
        frame.put("frameId", "frame-" + kind.toLowerCase());
        frame.put("kind", kind);
        frame.put("body", body);
        return writeJson(frame);
    }

    private static JsonNode sentReply(String frame) throws IOException {
        JsonNode channelFrame = OBJECT_MAPPER.readTree(frame);
        assertEquals("ACTION_REPLY", channelFrame.get("kind").asText());
        return OBJECT_MAPPER.readTree(channelFrame.get("body").asText());
    }

    private static String writeJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static com.xa.mass.client.worker.WorkerClient dummyWorkerClient() {
        return MassPlatform.builder()
                .baseUrl("http://localhost:8088")
                .build()
                .workers();
    }

    private static WorkerRuntimeDefinition realtimeDefinition() {
        return realtimeDefinition(dispatch -> WorkerActionResult.success("{}")).build();
    }

    private static WorkerRuntimeDefinition.Builder realtimeDefinition(WorkerActionHandler handler) {
        return WorkerRuntimeDefinition.builder()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .event("probe.realtime.metadata", handler);
    }

    private void startRealtimeControlPlaneServer() throws IOException {
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"ws-worker-001","workerGroupId":"realtime-probe","transportHint":"realtime"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + path);
        });
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static class RecordingWebSocket implements WebSocket {
        private final CountDownLatch sendLatch;
        private final List<String> sentTexts = new ArrayList<>();
        private volatile boolean outputClosed;
        private volatile boolean inputClosed;

        private RecordingWebSocket(CountDownLatch sendLatch) {
            this.sendLatch = sendLatch;
        }

        private List<String> sentTexts() {
            return sentTexts;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            assertTrue(last);
            sentTexts.add(data.toString());
            sendLatch.countDown();
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return outputClosed;
        }

        @Override
        public boolean isInputClosed() {
            return inputClosed;
        }

        @Override
        public void abort() {
            inputClosed = true;
            outputClosed = true;
        }
    }

    private static final class BlockingWebSocket extends RecordingWebSocket {
        private BlockingWebSocket() {
            super(new CountDownLatch(1));
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            assertTrue(last);
            return new CompletableFuture<>();
        }
    }

    private static final class CoordinatedFailingWebSocket extends RecordingWebSocket {
        private final CountDownLatch sendStarted;
        private final CountDownLatch allowFailure;

        private CoordinatedFailingWebSocket(CountDownLatch sendStarted, CountDownLatch allowFailure) {
            super(new CountDownLatch(1));
            this.sendStarted = sendStarted;
            this.allowFailure = allowFailure;
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            assertTrue(last);
            sendStarted.countDown();
            try {
                if (!allowFailure.await(2, TimeUnit.SECONDS)) {
                    return CompletableFuture.failedFuture(new AssertionError("send failure was not released"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.failedFuture(new IOException("send failed"));
        }
    }
}

