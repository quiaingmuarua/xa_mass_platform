package com.xa.mass.client.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerClientTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void workerRegistrationIsGroupFirstAndKeepsAttributesExplicit() throws Exception {
        List<String> observed = new ArrayList<>();
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            observed.add(method + " " + path);
            String body = readBody(exchange);
            if ("POST".equals(method) && "/worker-api/v1/worker-groups".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("phone-device-probe", request.get("groupId").asText());
                assertEquals("probe.phone.metadata", request.get("eventBindings").get(0).get("eventCode").asText());
                assertEquals("probeApp", request.get("eventBindings").get(0).get("projectCodes").get(0).asText());
                assertEquals("android", request.get("defaultAttributes").get("deviceFamily").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"groupId":"phone-device-probe","eventBindings":[{"eventCode":"probe.phone.metadata","projectCodes":["probeApp"]}],"defaultAttributes":{"deviceFamily":"android"},"defaultMaxConcurrentWork":20}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/worker-api/v1/workers".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("phone-worker-sg-001", request.get("workerId").asText());
                assertEquals("phone-device-probe", request.get("workerGroupId").asText());
                assertEquals("fp-android-13-sg", request.get("attributes").get("fingerprint").asText());
                assertFalse(request.has("adapterNodeId"), "worker registration must not carry topology ids");
                assertFalse(request.has("adapterId"), "worker registration must not carry adapter ids");
                assertFalse(request.has("eventBindings"), "worker registration must stay group-first");
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","workerGroupId":"phone-device-probe","transportHint":"polling"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        WorkerClient workers = platform().workers();
        WorkerGroupDeclarationResult group = workers.declareGroup(WorkerGroupSpec.builder()
                .groupId("phone-device-probe")
                .bindEvent("probe.phone.metadata", List.of("probeApp"))
                .defaultAttribute("deviceFamily", "android")
                .defaultMaxConcurrentWork(20)
                .build());
        WorkerRegistrationResult worker = workers.registerWorker(WorkerSpec.builder()
                .workerId("phone-worker-sg-001")
                .workerGroupId("phone-device-probe")
                .polling()
                .attribute("fingerprint", "fp-android-13-sg")
                .attribute("region", "sg")
                .build());

        assertEquals("phone-device-probe", group.groupId());
        assertEquals("polling", worker.transportHint());
        assertEquals(List.of(
                "POST /worker-api/v1/worker-groups",
                "POST /worker-api/v1/workers"
        ), observed);
    }

    @Test
    void directPollingWorkerCallsUseDocumentedRoutes() throws Exception {
        List<String> observed = new ArrayList<>();
        String sessionToken = "session-phone-worker-sg-001";
        startServer(exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            observed.add(method + " " + path);
            String body = readBody(exchange);
            if ("POST".equals(method) && "/worker-api/v1/workers/phone-worker-sg-001:online".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals(sessionToken, request.get("sessionToken").asText());
                assertEquals("startup", request.get("reason").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"online","transportHint":"polling"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/worker-api/v1/workers/phone-worker-sg-001:heartbeat".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals(sessionToken, request.get("sessionToken").asText());
                assertFalse(request.has("reason"));
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"heartbeat","transportHint":"polling"}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/worker-api/v1/workers/phone-worker-sg-001:poll".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals(10, request.get("maxMessages").asInt());
                assertEquals(500, request.get("timeoutMs").asLong());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","total":1,"items":[{"resultCorrelationRef":"corr-1","eventCode":"probe.phone.metadata","input":{"phone":"+14155550100"},"sharedConfig":{"routingCode":"sg"}}]}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/worker-api/v1/workers/phone-worker-sg-001:submit-result".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("corr-1", request.get("resultCorrelationRef").asText());
                assertTrue(request.get("success").asBoolean());
                assertEquals("{\"mcc\":\"525\",\"mnc\":\"01\"}", request.get("result").asText());
                assertFalse(request.hasNonNull("resultCode"));
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","resultCorrelationRef":"corr-1","submitted":true}}
                        """);
                return;
            }
            if ("POST".equals(method) && "/worker-api/v1/workers/phone-worker-sg-001:offline".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals(sessionToken, request.get("sessionToken").asText());
                assertEquals("shutdown", request.get("reason").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"workerId":"phone-worker-sg-001","action":"offline","transportHint":"polling"}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected " + method + " " + path);
        });

        WorkerClient workers = platform().workers();
        assertEquals("online", workers.online("phone-worker-sg-001", sessionToken, "startup").action());
        assertEquals("heartbeat", workers.heartbeat("phone-worker-sg-001", sessionToken, null).action());

        WorkerPollResult poll = workers.poll("phone-worker-sg-001", WorkerPollRequest.builder()
                .maxMessages(10)
                .timeoutMs(500L)
                .build());
        WorkerInvocation item = poll.items().getFirst();
        assertEquals("probe.phone.metadata", item.eventCode());
        assertEquals("+14155550100", item.input().getString("phone").orElseThrow());

        boolean submitted = workers.submitResult("phone-worker-sg-001",
                WorkerResultSubmission.success(item.resultCorrelationRef(), "{\"mcc\":\"525\",\"mnc\":\"01\"}"));
        assertTrue(submitted);

        assertEquals("offline", workers.offline("phone-worker-sg-001", sessionToken, "shutdown").action());

        assertEquals(List.of(
                "POST /worker-api/v1/workers/phone-worker-sg-001:online",
                "POST /worker-api/v1/workers/phone-worker-sg-001:heartbeat",
                "POST /worker-api/v1/workers/phone-worker-sg-001:poll",
                "POST /worker-api/v1/workers/phone-worker-sg-001:submit-result",
                "POST /worker-api/v1/workers/phone-worker-sg-001:offline"
        ), observed);
    }

    @Test
    void handlerAndRuntimeEvidenceKeepWorkerLocalFactsVisible() throws Exception {
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = readBody(exchange);
            if ("/worker-api/v1/workers/phone-worker-sg-001:report-handler-evidence".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("probe.phone.metadata", request.get("eventCodes").get(0).asText());
                assertEquals("fp-android-13-sg", request.get("attributes").get("fingerprint").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"status":"ACCEPTED","workerId":"phone-worker-sg-001","evidenceVersion":7,"accepted":true,"changed":true,"reason":"updated"}}
                        """);
                return;
            }
            if ("/worker-api/v1/workers/phone-worker-sg-001:report-runtime-evidence".equals(path)) {
                JsonNode request = OBJECT_MAPPER.readTree(body);
                assertEquals("DRAINING", request.get("state").asText());
                assertEquals("maintenance", request.get("reason").asText());
                respond(exchange, 200, """
                        {"code":0,"msg":"ok","data":{"status":"ACCEPTED","workerId":"phone-worker-sg-001","evidenceVersion":8,"accepted":true,"changed":true,"reason":"updated","snapshot":{"workerId":"phone-worker-sg-001","evidenceVersion":8,"state":"DRAINING","reason":"maintenance","observedAt":"2026-05-20T10:00:00Z","acceptedAt":"2026-05-20T10:00:01Z"}}}
                        """);
                return;
            }
            respond(exchange, 404, "unexpected");
        });

        WorkerClient workers = platform().workers();
        WorkerHandlerEvidenceResult handlerEvidence = workers.reportHandlerEvidence("phone-worker-sg-001",
                WorkerHandlerEvidence.builder()
                        .workerId("phone-worker-sg-001")
                        .evidenceVersion(7L)
                        .eventCode("probe.phone.metadata")
                        .attribute("fingerprint", "fp-android-13-sg")
                        .attribute("region", "sg")
                        .agentVersion("1.0.0")
                        .build());
        WorkerRuntimeEvidenceResult runtimeEvidence = workers.reportRuntimeEvidence("phone-worker-sg-001",
                WorkerRuntimeEvidence.builder()
                        .workerId("phone-worker-sg-001")
                        .evidenceVersion(8L)
                        .draining()
                        .reason("maintenance")
                        .attribute("queueDepth", "10")
                        .build());

        assertTrue(handlerEvidence.accepted());
        assertEquals("DRAINING", runtimeEvidence.snapshot().state());
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
