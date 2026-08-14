package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityRpcMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void uploadsRunsAndDownloadsSixServerScenarioOutputs()
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
        Path workerLab = temporaryDirectory.resolve("scenario-workers");
        createWorkerLab(workerLab);
        Path results = temporaryDirectory.resolve("results");

        try (FakeScenarioServer server = FakeScenarioServer.start()) {
            WorkerCapabilityRpcMain.main(new String[]{
                    "--scenario-id=proof-1000",
                    "--server-base-url=" + server.baseUri(),
                    "--phone-seed-path=" + phoneSeed,
                    "--string-seed-path=" + stringSeed,
                    "--result-dir=" + results,
                    "--scenario-worker-lab-root=" + workerLab,
                    "--load-interval-millis=25",
                    "--maximum-load-rounds=40",
                    "--request-timeout-millis=10000"
            });

            assertEquals(2, server.uploadCount());
            assertEquals(6, server.createCount());
            assertEquals(6, server.runCount());
            assertEquals(List.of(25L, 25L, 25L, 25L, 25L, 25L),
                    server.loadIntervals());
            assertEquals(List.of(40, 40, 40, 40, 40, 40),
                    server.maximumLoadRounds());
        }

        Path proofResults = results.resolve("proof-1000");
        List<Path> outputFiles;
        try (var files = Files.list(proofResults)) {
            outputFiles = files.sorted().toList();
        }
        assertEquals(6, outputFiles.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Path outputFile : outputFiles) {
            Files.readAllLines(outputFile, StandardCharsets.UTF_8)
                    .stream()
                    .map(Jsons::parseObject)
                    .forEach(rows::add);
        }
        assertEquals(60, rows.size());
        rows.forEach(row -> {
            assertFalse(row.containsKey("taskId"));
            assertFalse(row.containsKey("workerId"));
            assertFalse(row.containsKey("score"));
        });
    }

    private Path writeLines(String name, List<String> lines)
            throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    private static void createWorkerLab(Path root) throws IOException {
        for (String group : List.of(
                "scenario-phone-number-workers",
                "scenario-string-utils-workers"
        )) {
            Path directory = Files.createDirectories(root.resolve(group));
            for (int index = 0; index < 10; index++) {
                Files.writeString(
                        directory.resolve("worker-" + index + ".json"),
                        Jsons.toJson(Map.of(
                                "schemaVersion", 1,
                                "workerId", UUID.randomUUID().toString()
                        )),
                        StandardCharsets.UTF_8
                );
            }
        }
    }

    private static final class FakeScenarioServer implements AutoCloseable {
        private static final Map<String, Expected> EXPECTED = Map.of(
                "phonenumber.e164",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber",
                        "e164"
                ),
                "phonenumber.country",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber",
                        "countryCallingCode"
                ),
                "phonenumber.original-carrier",
                new Expected(
                        "scenario-phone-number-workers",
                        "rawNumber",
                        "originalCarrier"
                ),
                "string.md5",
                new Expected(
                        "scenario-string-utils-workers",
                        "value",
                        "md5"
                ),
                "string.sha1",
                new Expected(
                        "scenario-string-utils-workers",
                        "value",
                        "sha1"
                ),
                "string.base64.encode",
                new Expected(
                        "scenario-string-utils-workers",
                        "value",
                        "base64"
                )
        );

        private final HttpServer server;
        private final Map<String, String> inputs = new ConcurrentHashMap<>();
        private final Map<String, String> outputs = new ConcurrentHashMap<>();
        private final Map<String, String> scenarioTypes =
                new ConcurrentHashMap<>();
        private final AtomicInteger uploads = new AtomicInteger();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger runs = new AtomicInteger();
        private final List<Long> loadIntervals =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<Integer> maximumLoadRounds =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private FakeScenarioServer(HttpServer server) {
            this.server = server;
        }

        static FakeScenarioServer start() throws IOException {
            HttpServer http = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            FakeScenarioServer fake = new FakeScenarioServer(http);
            http.createContext("/api/v1/scenario-rpc", fake::handle);
            http.start();
            return fake;
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith(
                        "/api/v1/scenario-rpc/input-files/"
                )) {
                    handleUpload(exchange, path);
                } else if (path.equals(
                        "/api/v1/scenario-rpc/scenarios"
                )) {
                    handleCreate(exchange);
                } else if (path.startsWith(
                        "/api/v1/scenario-rpc/scenarios/"
                ) && path.endsWith(":run")) {
                    handleRun(exchange, path);
                } else if (path.startsWith(
                        "/api/v1/scenario-rpc/output-files/"
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

        private void handleCreate(HttpExchange exchange) throws IOException {
            Map<String, Object> request = request(exchange);
            String scenarioType = (String) request.get("scenarioType");
            if (!EXPECTED.containsKey(scenarioType)) {
                throw new IllegalArgumentException("unknown scenario type");
            }
            String scenarioId = "scenario-" + creates.incrementAndGet();
            scenarioTypes.put(scenarioId, scenarioType);
            respond(exchange, 201, Map.of(
                    "scenarioId", scenarioId,
                    "scenarioType", scenarioType,
                    "status", "created"
            ));
        }

        private void handleRun(HttpExchange exchange, String path)
                throws IOException {
            String scenarioId = path.substring(
                    "/api/v1/scenario-rpc/scenarios/".length(),
                    path.length() - ":run".length()
            );
            String scenarioType = scenarioTypes.get(scenarioId);
            if (scenarioType == null) {
                throw new IllegalArgumentException("unknown scenario");
            }
            Map<String, Object> request = request(exchange);
            String inputFile = (String) request.get("inputFile");
            long loadInterval = ((Number) request.get(
                    "loadIntervalMillis"
            )).longValue();
            int loadRounds = ((Number) request.get(
                    "maximumLoadRounds"
            )).intValue();
            Expected expected = EXPECTED.get(scenarioType);
            List<String> lines = inputs.get(inputFile).lines().toList();
            int run = runs.incrementAndGet();
            loadIntervals.add(loadInterval);
            maximumLoadRounds.add(loadRounds);
            String outputFile = scenarioId + ".jsonl";
            List<String> encoded = new ArrayList<>();
            for (int index = 0; index < lines.size(); index++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("workerGroupId", expected.workerGroupId());
                row.put(
                        "messageId",
                        "rpc-" + run + "-" + scenarioType + "-" + index
                );
                row.put("eventCode", scenarioType);
                row.put(
                        "input",
                        Map.of(expected.inputField(), lines.get(index))
                );
                row.put(
                        "result",
                        Map.of(
                                "valid", true,
                                expected.resultField(), "result-" + index
                        )
                );
                encoded.add(Jsons.toJson(row));
            }
            outputs.put(outputFile, String.join("\n", encoded) + "\n");
            respond(exchange, 200, Map.of(
                    "scenarioId", scenarioId,
                    "status", "succeeded",
                    "inputFile", inputFile,
                    "outputFile", outputFile,
                    "inputCount", lines.size(),
                    "resultCount", lines.size(),
                    "remainingCount", 0,
                    "loadRounds", 1,
                    "durationMillis", 1
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

        int createCount() {
            return creates.get();
        }

        int runCount() {
            return runs.get();
        }

        List<Long> loadIntervals() {
            return List.copyOf(loadIntervals);
        }

        List<Integer> maximumLoadRounds() {
            return List.copyOf(maximumLoadRounds);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record Expected(
                String workerGroupId,
                String inputField,
                String resultField
        ) {
        }
    }
}
