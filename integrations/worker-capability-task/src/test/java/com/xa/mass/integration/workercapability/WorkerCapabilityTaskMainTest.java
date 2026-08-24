package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityTaskMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAppendsApprovesAndExportsTwoFiniteTasks() throws Exception {
        Path phoneSeed = writeLines(
                "phone.txt",
                List.of(
                        "+8613800138000", "+14155552671",
                        "+442071838750", "+81312345678",
                        "+33142345678", "+61293744000",
                        "+4930123456", "+74951234567",
                        "+551155256325", "+919876543210"
                )
        );
        Path stringSeed = writeLines(
                "strings.txt",
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        );
        Path results = temporaryDirectory.resolve("results");

        try (FakeFiniteTaskServer server = FakeFiniteTaskServer.start()) {
            WorkerCapabilityTaskMain.main(new String[]{
                    "--proof-id=proof-1000",
                    "--server-base-url=" + server.baseUri(),
                    "--phone-seed-path=" + phoneSeed,
                    "--string-seed-path=" + stringSeed,
                    "--result-dir=" + results,
                    "--maximum-wait-millis=40000",
                    "--request-timeout-millis=10000"
            });

            assertEquals(2, server.createCount());
            assertEquals(2, server.appendCount());
            assertEquals(2, server.approveCount());
            assertEquals(2, server.exportCount());
            assertEquals(List.of(30, 30), server.appendSizes());
            assertEquals(List.of(40000L, 40000L), server.waitTimeouts());
            assertEquals(
                    List.of(
                            "scenario-phone-number-workers",
                            "scenario-string-utils-workers"
                    ),
                    server.workerGroupIds()
            );
        }

        Path proofResults = results.resolve("proof-1000");
        List<Path> outputFiles;
        try (var files = Files.list(proofResults)) {
            outputFiles = files.sorted().toList();
        }
        assertEquals(1, outputFiles.size());
        assertEquals(
                "capability-task-evidence.json",
                outputFiles.get(0).getFileName().toString()
        );
        String encoded = Files.readString(
                outputFiles.get(0),
                StandardCharsets.UTF_8
        );
        Map<String, Object> evidence = Jsons.parseObject(encoded);
        assertEquals("succeeded", evidence.get("status"));
        assertEquals(2, ((Number) evidence.get("taskCount")).intValue());
        assertEquals(60, ((Number) evidence.get("itemCount")).intValue());
        assertEquals(60, ((Number) evidence.get("resultCount")).intValue());
        assertEquals(60, ((List<?>) evidence.get("messageIds")).size());
        assertEquals(
                6,
                ((Map<?, ?>) evidence.get("eventResultCounts")).size()
        );
        assertEquals(List.of(), evidence.get("failures"));
        assertFalse(encoded.contains("+8613800138000"));
        assertFalse(encoded.contains("opaque-result"));
        assertFalse(encoded.contains("\"payload\""));
        assertTrue(encoded.contains("extension.worker.string.md5"));
    }

    private Path writeLines(String name, List<String> lines)
            throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    private static final class FakeFiniteTaskServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger taskSequence = new AtomicInteger();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger appends = new AtomicInteger();
        private final AtomicInteger approves = new AtomicInteger();
        private final AtomicInteger exports = new AtomicInteger();
        private final Map<String, List<Map<String, Object>>> taskItems =
                new ConcurrentHashMap<>();
        private final List<Integer> appendSizes =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Long> waitTimeouts =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> workerGroupIds =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private FakeFiniteTaskServer(HttpServer server) {
            this.server = server;
        }

        static FakeFiniteTaskServer start() throws IOException {
            HttpServer http = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            FakeFiniteTaskServer fake = new FakeFiniteTaskServer(http);
            http.createContext("/api/v1/tasks", fake::handle);
            http.start();
            return fake;
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if ("/api/v1/tasks".equals(path)) {
                    handleCreate(exchange);
                } else if (path.endsWith("/items")) {
                    handleAppend(exchange, taskId(path, "/items"));
                } else if (path.endsWith("/approve")) {
                    handleApprove(exchange, taskId(path, "/approve"));
                } else if (path.endsWith("/results:export")) {
                    handleExport(
                            exchange,
                            taskId(path, "/results:export")
                    );
                } else {
                    respondJson(exchange, 404, Map.of());
                }
            } catch (RuntimeException error) {
                respondJson(exchange, 500, Map.of("error", "invalid"));
            }
        }

        private void handleCreate(HttpExchange exchange) throws IOException {
            Map<String, Object> request = request(exchange);
            workerGroupIds.add((String) request.get("workerGroupId"));
            assertEquals(Map.of(), request.get("allocationRule"));
            assertEquals(50, ((Number) request.get("priority")).intValue());
            assertEquals(
                    10,
                    ((Number) request.get("maximumCandidateWorkers")).intValue()
            );
            assertEquals(
                    3,
                    ((Number) request.get("maxRetryTimes")).intValue()
            );
            String taskId = "task-proof-" + taskSequence.incrementAndGet();
            taskItems.put(taskId, new ArrayList<>());
            creates.incrementAndGet();
            respondJson(exchange, 201, Map.of(
                    "taskId", taskId,
                    "status", "created"
            ));
        }

        private void handleAppend(HttpExchange exchange, String taskId)
                throws IOException {
            Map<String, Object> request = request(exchange);
            List<Map<String, Object>> items = objectList(
                    request.get("items")
            );
            taskItems.get(taskId).addAll(items);
            appendSizes.add(items.size());
            appends.incrementAndGet();
            Map<String, Object> results = new LinkedHashMap<>();
            for (Map<String, Object> item : items) {
                assertFalse(item.containsKey("allocationRule"));
                results.put(
                        (String) item.get("messageId"),
                        Map.of("status", "appended")
                );
            }
            respondJson(exchange, 200, Map.of("results", results));
        }

        private void handleApprove(HttpExchange exchange, String taskId)
                throws IOException {
            if (!taskItems.containsKey(taskId)) {
                throw new IllegalArgumentException("unknown Task");
            }
            approves.incrementAndGet();
            respondJson(exchange, 200, Map.of("status", "approved"));
        }

        private void handleExport(HttpExchange exchange, String taskId)
                throws IOException {
            Map<String, Object> request = request(exchange);
            waitTimeouts.add(
                    ((Number) request.get("waitTimeoutMillis")).longValue()
            );
            List<String> rows = new ArrayList<>();
            for (Map<String, Object> item : taskItems.get(taskId)) {
                rows.add(Jsons.toJson(Map.of(
                        "messageId", item.get("messageId"),
                        "opaqueResultPayload", "opaque-result"
                )));
            }
            exports.incrementAndGet();
            byte[] body = (String.join("\n", rows) + "\n").getBytes(
                    StandardCharsets.UTF_8
            );
            exchange.getResponseHeaders().add(
                    "Content-Type",
                    "application/x-ndjson"
            );
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private static String taskId(String path, String suffix) {
            return path.substring(
                    "/api/v1/tasks/".length(),
                    path.length() - suffix.length()
            );
        }

        private static Map<String, Object> request(HttpExchange exchange)
                throws IOException {
            return Jsons.parseObject(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> objectList(Object value) {
            return ((List<?>) value).stream()
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }

        private static void respondJson(
                HttpExchange exchange,
                int status,
                Map<String, Object> body
        ) throws IOException {
            byte[] content = Jsons.toJson(body).getBytes(
                    StandardCharsets.UTF_8
            );
            exchange.getResponseHeaders().add(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(status, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        }

        URI baseUri() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
        }

        int createCount() {
            return creates.get();
        }

        int appendCount() {
            return appends.get();
        }

        int approveCount() {
            return approves.get();
        }

        int exportCount() {
            return exports.get();
        }

        List<Integer> appendSizes() {
            return List.copyOf(appendSizes);
        }

        List<Long> waitTimeouts() {
            return List.copyOf(waitTimeouts);
        }

        List<String> workerGroupIds() {
            return List.copyOf(workerGroupIds);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
