package com.xa.mass.client.worker.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.payload.MassPayloadException;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingWorkerSessionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void managedSessionStartsPollsHandlesDispatchSubmitsResultAndClosesOffline() throws Exception {
        List<String> observed = new ArrayList<>();
        CountDownLatch heartbeatSeen = new CountDownLatch(1);
        CountDownLatch resultSubmitted = new CountDownLatch(1);
        CountDownLatch offlineSeen = new CountDownLatch(1);
        AtomicBoolean firstPoll = new AtomicBoolean(true);
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            observed.add(method + " " + path);
            String body = readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("phone-worker-sg-001", request.get("workerId").asText());
                assertEquals("phone-device-probe", request.get("workerGroupId").asText());
                assertFalse(request.has("adapterId"));
                assertEquals("polling", request.get("transportHint").asText());
                assertEquals("fp-android-13-sg", request.get("attributes").get("fingerprint").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","workerGroupId":"phone-device-probe","transportHint":"polling"}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:online".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"online","transportHint":"polling"}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:heartbeat".equals(path)) {
                heartbeatSeen.countDown();
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"heartbeat","transportHint":"polling"}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:poll".equals(path)) {
                if (firstPoll.getAndSet(false)) {
                    respond(exchange, 200, """
                            {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","total":1,"items":[{"resultCorrelationRef":"corr-1","eventCode":"probe.phone.metadata","input":{"phone":"+14155550100"},"sharedConfig":{"routingCode":"sg"}}]}}
                            """);
                } else {
                    respond(exchange, 200, """
                            {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","total":0,"items":[]}}
                            """);
                }
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:submit-result".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("corr-1", request.get("resultCorrelationRef").asText());
                assertTrue(request.get("success").asBoolean());
                JsonNode result = OBJECT_MAPPER.readTree(request.get("result").asText());
                assertEquals("+14155550100", result.get("phone").asText());
                assertEquals("525", result.get("mcc").asText());
                assertFalse(request.hasNonNull("resultCode"));
                resultSubmitted.countDown();
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","resultCorrelationRef":"corr-1","submitted":true}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:offline".equals(path)) {
                offlineSeen.countDown();
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"offline","transportHint":"polling"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        PollingWorkerSession session = platform().workerSessions().polling()
                .workerId("phone-worker-sg-001")
                .workerGroupId("phone-device-probe")
                .attribute("region", "sg")
                .attribute("fingerprint", "fp-android-13-sg")
                .event("probe.phone.metadata", dispatch -> {
                    String phone = dispatch.input().requiredString("phone");
                    return WorkerResult.success("""
                            {"phone":"%s","mcc":"525","mnc":"01"}
                            """.formatted(phone).trim());
                })
                .maxMessages(5)
                .pollInterval(Duration.ofMillis(20))
                .heartbeatInterval(Duration.ofMillis(50))
                .start();

        assertTrue(resultSubmitted.await(2, TimeUnit.SECONDS), "result should be submitted");
        assertTrue(heartbeatSeen.await(2, TimeUnit.SECONDS), "heartbeat should be sent");
        session.close();
        assertTrue(offlineSeen.await(2, TimeUnit.SECONDS), "close should mark worker offline");
        assertFalse(session.isRunning());
        assertTrue(observed.contains("POST /worker-api/v1/workers/phone-worker-sg-001:poll"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/phone-worker-sg-001:report-handler-evidence"));
        assertFalse(observed.contains("POST /worker-api/v1/workers/phone-worker-sg-001:report-runtime-evidence"));
    }

    @Test
    void startupFailureStopsBeforeHeartbeatAndPollAndReportsLastSuccessfulStep() throws Exception {
        List<String> observed = new ArrayList<>();
        AtomicReference<WorkerSessionStartupFailure> failureRef = new AtomicReference<>();
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            observed.add(exchange.getRequestMethod() + " " + path);
            readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                respond(exchange, 500, "register failed");
                return;
            }
            respond(exchange, 404, "unexpected");
        });

        WorkerSessionListener listener = new WorkerSessionListener() {
            @Override
            public void onStartupFailure(WorkerSessionStartupFailure failure) {
                failureRef.set(failure);
            }
        };

        WorkerSessionStartupException exception = assertThrows(WorkerSessionStartupException.class,
                () -> platform().workerSessions().polling()
                        .workerId("worker-1")
                        .workerGroupId("group-1")
                        .event("probe.phone.metadata", dispatch -> WorkerResult.success("{}"))
                        .listener(listener)
                        .start());

        assertEquals(WorkerSessionStartupStep.REGISTER_WORKER, exception.failure().failedStep());
        assertEquals(null, exception.failure().lastSuccessfulStep());
        assertEquals(exception.failure(), failureRef.get());
        assertEquals(List.of("POST /worker-api/v1/workers"), observed);
    }

    @Test
    void heartbeatFailureUsesDedicatedCallbackAndDoesNotReportPollFailure() throws Exception {
        CountDownLatch heartbeatFailed = new CountDownLatch(1);
        AtomicReference<WorkerSessionHeartbeatFailure> heartbeatFailure = new AtomicReference<>();
        AtomicBoolean pollFailureReported = new AtomicBoolean(false);
        AtomicInteger heartbeatAttempts = new AtomicInteger();
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","workerGroupId":"group-1","transportHint":"polling"}}
                        """);
                return;
            }
            if (path.endsWith(":online") || path.endsWith(":offline")) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","accepted":true}}
                        """);
                return;
            }
            if (path.endsWith(":heartbeat")) {
                heartbeatAttempts.incrementAndGet();
                respond(exchange, 500, "heartbeat failed");
                return;
            }
            if (path.endsWith(":poll")) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","total":0,"items":[]}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + path);
        });

        WorkerSessionListener listener = new WorkerSessionListener() {
            @Override
            public void onHeartbeatFailure(WorkerSessionHeartbeatFailure failure) {
                heartbeatFailure.set(failure);
                heartbeatFailed.countDown();
            }

            @Override
            public void onPollFailure(WorkerSessionPollFailure failure) {
                pollFailureReported.set(true);
            }
        };

        try (PollingWorkerSession ignored = platform().workerSessions().polling()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .event("probe.phone.metadata", dispatch -> WorkerResult.success("{}"))
                .pollInterval(Duration.ofMillis(20))
                .heartbeatInterval(Duration.ofMillis(20))
                .listener(listener)
                .start()) {
            assertTrue(heartbeatFailed.await(2, TimeUnit.SECONDS), "heartbeat failure should be reported");
        }

        assertEquals("worker-1", heartbeatFailure.get().workerId());
        assertEquals(1, heartbeatFailure.get().consecutiveFailures());
        assertTrue(heartbeatAttempts.get() >= 1);
        assertFalse(pollFailureReported.get(), "heartbeat failure must not be reported as poll failure");
    }

    @Test
    void handlerPayloadExceptionSubmitsStructuredFailedResult() throws Exception {
        CountDownLatch failedResultSubmitted = new CountDownLatch(1);
        AtomicReference<WorkerSessionDispatchFailure> handlerFailure = new AtomicReference<>();
        AtomicBoolean firstPoll = new AtomicBoolean(true);
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = readBody(exchange);
            if ("/worker-api/v1/workers".equals(path)) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","workerGroupId":"group-1","transportHint":"polling"}}
                        """);
                return;
            }
            if (path.endsWith(":online")) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","action":"online","transportHint":"polling"}}
                        """);
                return;
            }
            if (path.endsWith(":heartbeat")) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","action":"heartbeat","transportHint":"polling"}}
                        """);
                return;
            }
            if (path.endsWith(":poll")) {
                if (firstPoll.getAndSet(false)) {
                    respond(exchange, 200, """
                            {"code":0,"msg":"ok","data":{"workerId":"worker-1","total":1,"items":[{"resultCorrelationRef":"corr-1","eventCode":"probe.phone.metadata","input":{},"sharedConfig":{}}]}}
                            """);
                } else {
                    respond(exchange, 200, """
                            {"code":0,"msg":"ok","data":{"workerId":"worker-1","total":0,"items":[]}}
                            """);
                }
                return;
            }
            if (path.endsWith(":submit-result")) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("corr-1", request.get("resultCorrelationRef").asText());
                assertFalse(request.get("success").asBoolean());
                assertEquals("HANDLER_ERROR", request.get("resultCode").asText());
                assertTrue(request.get("result").asText().contains(MassPayloadException.class.getName()));
                failedResultSubmitted.countDown();
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","resultCorrelationRef":"corr-1","submitted":true}}
                        """);
                return;
            }
            if (path.endsWith(":offline")) {
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"worker-1","action":"offline","transportHint":"polling"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected");
        });

        WorkerSessionListener listener = new WorkerSessionListener() {
            @Override
            public void onHandlerFailure(WorkerSessionDispatchFailure failure) {
                handlerFailure.set(failure);
            }
        };

        try (PollingWorkerSession ignored = platform().workerSessions().polling()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .event("probe.phone.metadata", dispatch -> {
                    dispatch.input().requiredUri("url");
                    return WorkerResult.success("{}");
                })
                .pollInterval(Duration.ofMillis(20))
                .heartbeatInterval(Duration.ofMillis(50))
                .listener(listener)
                .start()) {
            assertTrue(failedResultSubmitted.await(2, TimeUnit.SECONDS), "failed result should be submitted");
        }

        assertInstanceOf(MassPayloadException.class, handlerFailure.get().cause());
    }

    @Test
    void workerResultKeepsOpaqueResultBody() {
        WorkerResult result = WorkerResult.success("{\"title\":null,\"statusCode\":204}");

        assertTrue(result.success());
        assertEquals("{\"title\":null,\"statusCode\":204}", result.result());
    }

    private MassPlatform platform() {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("mass_sk_worker")
                .build();
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
}

