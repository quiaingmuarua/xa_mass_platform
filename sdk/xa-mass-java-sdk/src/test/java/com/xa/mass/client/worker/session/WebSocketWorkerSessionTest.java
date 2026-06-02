package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketWorkerSessionTest {
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
            if ("/worker-api/v1/adapter-nodes".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("ws-node-sg-1", request.get("adapterNodeId").asText());
                assertEquals("websocket", request.get("adapterType").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"adapterNodeId":"ws-node-sg-1","adapterType":"websocket","endpointId":"ws-node-sg-1","enabled":true,"online":true,"attributes":{"region":"sg"}}}
                        """);
                return;
            }
            if ("/worker-api/v1/node-group-bindings".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("realtime-probe", request.get("workerGroupId").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"adapterNodeId":"ws-node-sg-1","workerGroupId":"realtime-probe","enabled":true,"draining":false,"attributes":{"region":"sg"}}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("ws-worker-001", request.get("workerId").asText());
                assertEquals("websocket", request.get("adapterId").asText());
                assertEquals("realtime", request.get("transportHint").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"ws-worker-001","adapterNodeId":"ws-node-sg-1","workerGroupId":"realtime-probe","adapterId":"websocket","transportHint":"realtime"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        try (WebSocketWorkerSession session = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .attribute("region", "sg")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .routeKey("route-worker-001")
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of(
                        "workerId", dispatch.workerId(),
                        "title", dispatch.input().requiredString("title"),
                        "integrationProbe", "java-sdk-websocket-session"
                )))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    connectedUri.set(uri);
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            assertTrue(session.isRunning());
            assertEquals("workerId=ws-worker-001&routeKey=route-worker-001", connectedUri.get().getRawQuery());

            listenerRef.get().onText(webSocket, """
                    {"messageId":"msg-1","taskId":"task-1","eventCode":"probe.realtime.metadata","workerId":"ws-worker-001","input":{"title":"hello"},"sharedConfig":{"routingCode":"sg"}}
                    """, true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(resultSent.await(2, TimeUnit.SECONDS), "result frame should be sent");
            JsonNode result = OBJECT_MAPPER.readTree(webSocket.sentTexts().get(0));
            assertEquals("msg-1", result.get("messageId").asText());
            assertEquals("task-1", result.get("taskId").asText());
            assertTrue(result.get("success").asBoolean());
            assertEquals("hello", result.get("output").get("title").asText());
            assertEquals("java-sdk-websocket-session", result.get("output").get("integrationProbe").asText());
            assertEquals(0, session.pendingResults());
        }

        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:online"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:heartbeat"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:offline"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:report-capability"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/ws-worker-001:report-state"));
    }

    @Test
    void controlFrameWithoutTaskIdentityIsIgnored() throws Exception {
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(resultSent);
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerSession ignored = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of()))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, """
                    {"eventCode":"__control.close","reason":"compatibility-control-frame"}
                    """, true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertFalse(resultSent.await(150, TimeUnit.MILLISECONDS), "control frame should not emit a result");
            assertTrue(webSocket.sentTexts().isEmpty());
        }
    }

    @Test
    void unknownEventSendsStructuredFailureResult() throws Exception {
        CountDownLatch resultSent = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(resultSent);
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerSession ignored = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of()))
                .connectTimeout(Duration.ofSeconds(1))
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            listenerRef.get().onText(webSocket, """
                    {"messageId":"msg-2","taskId":"task-2","eventCode":"probe.unknown","workerId":"ws-worker-001","input":{},"sharedConfig":{}}
                    """, true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(resultSent.await(2, TimeUnit.SECONDS), "failure result frame should be sent");
            JsonNode result = OBJECT_MAPPER.readTree(webSocket.sentTexts().get(0));
            assertFalse(result.get("success").asBoolean());
            assertEquals("NO_HANDLER", result.get("errorCode").asText());
        }
    }

    @Test
    void closeAbandonsQueuedResultWhenSocketIsUnavailable() throws Exception {
        CountDownLatch abandoned = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerSessionQueuedResultFailure> failureRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        WebSocketWorkerSession session = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of()))
                .connectTimeout(Duration.ofMillis(100))
                .listener(new WorkerSessionListener() {
                    @Override
                    public void onQueuedResultAbandoned(WorkerSessionQueuedResultFailure failure) {
                        failureRef.set(failure);
                        abandoned.countDown();
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
            listenerRef.get().onText(webSocket, """
                    {"messageId":"msg-close","taskId":"task-close","eventCode":"probe.realtime.metadata","workerId":"ws-worker-001","input":{},"sharedConfig":{}}
                    """, true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            session.close();

            assertTrue(abandoned.await(2, TimeUnit.SECONDS), "close should abandon queued result");
            assertEquals(WorkerSessionQueuedResultFailure.Reason.SESSION_CLOSED, failureRef.get().reason());
            assertEquals("msg-close", failureRef.get().dispatch().messageId());
        } finally {
            session.close();
        }
    }

    @Test
    void fullQueueDropsResultWithSpecificCallback() throws Exception {
        CountDownLatch dropped = new CountDownLatch(1);
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerSessionQueuedResultFailure> failureRef = new AtomicReference<>();
        BlockingWebSocket webSocket = new BlockingWebSocket();
        startRealtimeControlPlaneServer();

        try (WebSocketWorkerSession ignored = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of()))
                .connectTimeout(Duration.ofSeconds(1))
                .outboundQueueCapacity(1)
                .listener(new WorkerSessionListener() {
                    @Override
                    public void onQueuedResultDropped(WorkerSessionQueuedResultFailure failure) {
                        failureRef.set(failure);
                        dropped.countDown();
                    }
                })
                .webSocketConnector((uri, listener) -> {
                    listenerRef.set(listener);
                    listener.onOpen(webSocket);
                    return CompletableFuture.completedFuture(webSocket);
                })
                .start()) {
            for (int i = 1; i <= 3; i++) {
                listenerRef.get().onText(webSocket, """
                        {"messageId":"msg-%d","taskId":"task-drop","eventCode":"probe.realtime.metadata","workerId":"ws-worker-001","input":{},"sharedConfig":{}}
                        """.formatted(i), true).toCompletableFuture().get(1, TimeUnit.SECONDS);
            }

            assertTrue(dropped.await(2, TimeUnit.SECONDS), "full queue should drop a result");
            assertEquals(WorkerSessionQueuedResultFailure.Reason.QUEUE_FULL, failureRef.get().reason());
        }
    }

    @Test
    void reconnectExhaustionAbandonsQueuedResult() throws Exception {
        CountDownLatch abandoned = new CountDownLatch(1);
        AtomicInteger connectAttempts = new AtomicInteger();
        AtomicReference<WebSocket.Listener> listenerRef = new AtomicReference<>();
        AtomicReference<WorkerSessionQueuedResultFailure> failureRef = new AtomicReference<>();
        RecordingWebSocket webSocket = new RecordingWebSocket(new CountDownLatch(1));
        startRealtimeControlPlaneServer();

        WebSocketWorkerSession session = platform().workerSessions().webSocket()
                .workerId("ws-worker-001")
                .workerGroupId("realtime-probe")
                .adapterNodeId("ws-node-sg-1")
                .endpoint(URI.create("ws://127.0.0.1:18080/ws"))
                .event("probe.realtime.metadata", dispatch -> WorkerResult.success(Map.of()))
                .connectTimeout(Duration.ofMillis(100))
                .reconnectBackoff(Duration.ofMillis(10))
                .maxReconnectAttempts(1)
                .listener(new WorkerSessionListener() {
                    @Override
                    public void onQueuedResultAbandoned(WorkerSessionQueuedResultFailure failure) {
                        failureRef.set(failure);
                        abandoned.countDown();
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
            listenerRef.get().onText(webSocket, """
                    {"messageId":"msg-reconnect","taskId":"task-reconnect","eventCode":"probe.realtime.metadata","workerId":"ws-worker-001","input":{},"sharedConfig":{}}
                    """, true).toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertTrue(abandoned.await(2, TimeUnit.SECONDS), "reconnect exhaustion should abandon queued result");
            assertEquals(WorkerSessionQueuedResultFailure.Reason.RECONNECT_EXHAUSTED, failureRef.get().reason());
            assertEquals("msg-reconnect", failureRef.get().dispatch().messageId());
            assertFalse(session.isRunning());
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

    private void startRealtimeControlPlaneServer() throws IOException {
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            readBody(exchange);
            if ("/worker-api/v1/adapter-nodes".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"adapterNodeId":"ws-node-sg-1","adapterType":"websocket","endpointId":"ws-node-sg-1","enabled":true,"online":true,"attributes":{}}}
                        """);
                return;
            }
            if ("/worker-api/v1/node-group-bindings".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"adapterNodeId":"ws-node-sg-1","workerGroupId":"realtime-probe","enabled":true,"draining":false,"attributes":{}}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"ws-worker-001","adapterNodeId":"ws-node-sg-1","workerGroupId":"realtime-probe","adapterId":"websocket","transportHint":"realtime"}}
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
}
