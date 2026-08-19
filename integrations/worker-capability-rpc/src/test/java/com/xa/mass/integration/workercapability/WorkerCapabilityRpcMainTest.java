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

class WorkerCapabilityRpcMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsRunsAndDownloadsSixServerTaskBatchOutputs()
            throws Exception {
        Path phoneSeed = writeLines(
                "phone.txt",
                List.of(
                        "+8613800138000",
                        "+14155552671",
                        "+442071838750",
                        "+81312345678",
                        "+33142345678",
                        "+61293744000",
                        "+4930123456",
                        "+74951234567",
                        "+551155256325",
                        "+919876543210"
                )
        );
        Path stringSeed = writeLines(
                "strings.txt",
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        );
        Path results = temporaryDirectory.resolve("results");

        try (FakeTaskBatchServer server = FakeTaskBatchServer.start()) {
            WorkerCapabilityRpcMain.main(new String[]{
                    "--proof-id=proof-1000",
                    "--server-base-url=" + server.baseUri(),
                    "--phone-seed-path=" + phoneSeed,
                    "--string-seed-path=" + stringSeed,
                    "--result-dir=" + results,
                    "--maximum-wait-millis=40000",
                    "--request-timeout-millis=10000"
            });

            assertEquals(2, server.uploadCount());
            assertEquals(6, server.runCount());
            assertEquals(
                    List.of(40000L, 40000L, 40000L, 40000L, 40000L, 40000L),
                    server.maximumWaitMillis()
            );
        }

        Path proofResults = results.resolve("proof-1000");
        List<Path> outputFiles;
        try (var files = Files.list(proofResults)) {
            outputFiles = files.sorted().toList();
        }
        assertEquals(1, outputFiles.size());
        assertEquals(
                "task-batch-evidence.json",
                outputFiles.get(0).getFileName().toString()
        );
        String encoded = Files.readString(
                outputFiles.get(0),
                StandardCharsets.UTF_8
        );
        Map<String, Object> evidence = Jsons.parseObject(encoded);
        assertEquals("succeeded", evidence.get("status"));
        assertEquals(6, ((Number) evidence.get("batchCount")).intValue());
        assertEquals(60, ((Number) evidence.get("inputCount")).intValue());
        assertEquals(60, ((Number) evidence.get("resultCount")).intValue());
        assertEquals(0, ((Number) evidence.get("remainingCount")).intValue());
        assertEquals(60, ((List<?>) evidence.get("messageIds")).size());
        assertEquals(Map.of(), evidence.get("missingResultCounts"));
        assertEquals(List.of(), evidence.get("duplicateMessageIds"));
        assertFalse(encoded.contains("result-0"));
        assertFalse(encoded.contains("evolvedPayload"));
        assertFalse(encoded.contains("valid"));
        assertTrue(encoded.contains("extension.worker.string.md5"));
    }

    private Path writeLines(String name, List<String> lines)
            throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    private static final class FakeTaskBatchServer implements AutoCloseable {
        private static final Map<String, Expected> EXPECTED = Map.of(
                "extension.worker.phonenumber.e164",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber"
                ),
                "extension.worker.phonenumber.country",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber"
                ),
                "extension.worker.phonenumber.original-carrier",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber"
                ),
                "extension.worker.string.md5",
                new Expected(
                        "scenario-string-utils-workers",
                        "value"
                ),
                "extension.worker.string.sha1",
                new Expected(
                        "scenario-string-utils-workers",
                        "value"
                ),
                "extension.worker.string.base64.encode",
                new Expected(
                        "scenario-string-utils-workers",
                        "value"
                )
        );

        private final HttpServer server;
        private final Map<String, String> inputs = new ConcurrentHashMap<>();
        private final Map<String, String> outputs = new ConcurrentHashMap<>();
        private final AtomicInteger uploads = new AtomicInteger();
        private final AtomicInteger runs = new AtomicInteger();
        private final List<Long> maximumWaitMillis =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private FakeTaskBatchServer(HttpServer server) {
            this.server = server;
        }

        static FakeTaskBatchServer start() throws IOException {
            HttpServer http = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            FakeTaskBatchServer fake = new FakeTaskBatchServer(http);
            http.createContext("/api/v1/task-batches", fake::handle);
            http.start();
            return fake;
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith(
                        "/api/v1/task-batches/input-files/"
                )) {
                    handleUpload(exchange, path);
                } else if (path.equals(
                        "/api/v1/task-batches/runs"
                )) {
                    handleRun(exchange);
                } else if (path.startsWith(
                        "/api/v1/task-batches/output-files/"
                )) {
                    handleDownload(exchange, path);
                } else {
                    respond(exchange, 404, Map.of());
                }
            } catch (RuntimeException error) {
                respond(exchange, 500, Map.of("error", error.getMessage()));
            }
        }

        private void handleUpload(HttpExchange exchange, String path)
                throws IOException {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            inputs.put(fileName, new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            uploads.incrementAndGet();
            respond(exchange, 200, Map.of(
                    "fileName", fileName,
                    "byteCount", inputs.get(fileName).length(),
                    "lineCount", inputs.get(fileName).lines().count()
            ));
        }

        private void handleRun(HttpExchange exchange)
                throws IOException {
            Map<String, Object> request = request(exchange);
            String workerGroupId = (String) request.get("workerGroupId");
            String eventCode = (String) request.get("eventCode");
            String payloadKey = (String) request.get("payloadKey");
            String inputFile = (String) request.get("inputFile");
            long maximumWait = ((Number) request.get(
                    "maximumWaitMillis"
            )).longValue();
            Expected expected = EXPECTED.get(eventCode);
            if (expected == null
                    || !expected.workerGroupId().equals(workerGroupId)
                    || !expected.inputField().equals(payloadKey)) {
                throw new IllegalArgumentException("invalid Task Batch");
            }
            List<String> lines = inputs.get(inputFile).lines().toList();
            int run = runs.incrementAndGet();
            maximumWaitMillis.add(maximumWait);
            String runId = "task-batch-" + run;
            String outputFile = runId + ".jsonl";
            List<String> encoded = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("workerGroupId", expected.workerGroupId());
                row.put(
                        "messageId",
                        runId + "-" + eventCode + "-" + index
                );
                row.put("eventCode", eventCode);
                row.put(
                        "input",
                        Map.of(expected.inputField(), lines.get(index))
                );
                row.put(
                        "result",
                        Map.of(
                                "evolvedPayload",
                                Map.of("value", "result-" + index)
                        )
                );
                encoded.add(Jsons.toJson(row));
            }
            outputs.put(outputFile, String.join("\n", encoded) + "\n");
            respond(exchange, 200, Map.ofEntries(
                    Map.entry("runId", runId),
                    Map.entry("workerGroupId", workerGroupId),
                    Map.entry("eventCode", eventCode),
                    Map.entry("payloadKey", payloadKey),
                    Map.entry("status", "succeeded"),
                    Map.entry("inputFile", inputFile),
                    Map.entry("outputFile", outputFile),
                    Map.entry("inputCount", lines.size()),
                    Map.entry("resultCount", lines.size()),
                    Map.entry("remainingCount", 0),
                    Map.entry("loadRounds", 1),
                    Map.entry("durationMillis", 1)
            ));
        }

        private static Map<String, Object> request(HttpExchange exchange)
                throws IOException {
            return Jsons.parseObject(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
        }

        private void handleDownload(HttpExchange exchange, String path)
                throws IOException {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            byte[] content = outputs.get(fileName).getBytes(
                    StandardCharsets.UTF_8
            );
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        }

        private static void respond(
                HttpExchange exchange,
                int status,
                Map<String, Object> body
        ) throws IOException {
            byte[] content = Jsons.toJson(body).getBytes(
                    StandardCharsets.UTF_8
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

        int uploadCount() {
            return uploads.get();
        }

        int runCount() {
            return runs.get();
        }

        List<Long> maximumWaitMillis() {
            return List.copyOf(maximumWaitMillis);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Expected(
                String workerGroupId,
                String inputField
        ) {
        }
    }
}
