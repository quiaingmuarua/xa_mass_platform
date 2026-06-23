package com.xa.mass.client.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.WorkerRuntimeEvidence;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeReporterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsHandlerEvidenceFromRuntimeDefinition() throws Exception {
        List<String> observedPaths = new ArrayList<>();
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            observedPaths.add(path);
            JsonNode request = OBJECT_MAPPER.readTree(readBody(exchange));
            assertEquals("/worker-api/v1/workers/worker-1:report-handler-evidence", path);
            assertEquals("worker-1", request.get("workerId").asText());
            assertEquals("probe.phone.metadata", request.get("eventCodes").get(0).asText());
            assertEquals("sg", request.get("attributes").get("region").asText());
            respond(exchange, 200, """
                    {"code":0,"msg":"ok","data":{"status":"ACCEPTED","workerId":"worker-1","evidenceVersion":1,"accepted":true,"changed":true}}
                    """);
        });

        MassPlatform platform = platform();
        WorkerRuntimeDefinition definition = definition();

        assertTrue(platform.workerRuntimes().reporter(definition).reportHandlerEvidence().accepted());
        assertEquals(List.of("/worker-api/v1/workers/worker-1:report-handler-evidence"), observedPaths);
    }

    @Test
    void runtimeReporterUsesRuntimeWorkerIdForRuntimeEvidence() throws Exception {
        startServer(exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            JsonNode request = OBJECT_MAPPER.readTree(readBody(exchange));
            assertEquals("/worker-api/v1/workers/worker-1:report-runtime-evidence", path);
            assertEquals("worker-1", request.get("workerId").asText());
            assertEquals("AVAILABLE", request.get("state").asText());
            assertEquals("ready", request.get("reason").asText());
            respond(exchange, 200, """
                    {"code":0,"msg":"ok","data":{"status":"ACCEPTED","workerId":"worker-1","evidenceVersion":2,"accepted":true,"changed":true}}
                    """);
        });

        WorkerRuntimeReporter reporter = platform().workerRuntimes().reporter(definition());

        assertTrue(reporter.reportRuntimeEvidence(WorkerRuntimeEvidence.builder()
                .workerId("wrong-worker")
                .available()
                .reason("ready")
                .observedAt(Instant.parse("2026-06-18T00:00:00Z"))
                .build()).accepted());
    }

    private MassPlatform platform() {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("mass_sk_worker")
                .build();
    }

    private static WorkerRuntimeDefinition definition() {
        return WorkerRuntimeDefinition.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .attribute("region", "sg")
                .event("probe.phone.metadata", dispatch -> WorkerActionResult.success("{}"))
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
