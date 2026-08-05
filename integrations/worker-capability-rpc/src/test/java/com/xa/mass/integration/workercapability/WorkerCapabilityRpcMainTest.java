package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityRpcMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesIndependentGroupResultsUsingRegisteredWorkerIds()
            throws Exception {
        try (FakeRuntimeApi api = FakeRuntimeApi.start(false)) {
            ScenarioFiles files = scenarioFiles("complete");

            WorkerCapabilityRpcMain.main(arguments(api, files));

            List<Map<String, Object>> phone = readJsonLines(
                    files.scenarioResults().resolve("phone-number.jsonl")
            );
            List<Map<String, Object>> strings = readJsonLines(
                    files.scenarioResults().resolve("string-utils.jsonl")
            );
            assertEquals(30, phone.size());
            assertEquals(30, strings.size());
            assertEquals(
                    Set.of("scenario-phone-number-workers"),
                    values(phone, "workerGroupId")
            );
            assertEquals(
                    Set.of("scenario-string-utils-workers"),
                    values(strings, "workerGroupId")
            );
            assertEquals(10, values(phone, "workerId").size());
            assertEquals(10, values(strings, "workerId").size());
            assertEquals(20, api.registeredWorkerIds.size());
            assertEquals(
                    api.registeredWorkerIds,
                    api.allocatedWorkerIds
            );
            assertEquals(2, api.closedTaskCount.get());
            assertTrue(phone.stream().allMatch(result ->
                    result.containsKey("clientWorkerKey")
            ));
            assertTrue(strings.stream().allMatch(result ->
                    result.containsKey("clientWorkerKey")
            ));
        }
    }

    @Test
    void preservesPhoneResultsWhenStringScenarioFails()
            throws Exception {
        try (FakeRuntimeApi api = FakeRuntimeApi.start(true)) {
            ScenarioFiles files = scenarioFiles("partial");

            assertThrows(
                    IllegalStateException.class,
                    () -> WorkerCapabilityRpcMain.main(
                            arguments(api, files)
                    )
            );

            assertEquals(
                    30,
                    readJsonLines(files.scenarioResults().resolve(
                            "phone-number.jsonl"
                    )).size()
            );
            assertFalse(Files.exists(files.scenarioResults().resolve(
                    "string-utils.jsonl"
            )));
            assertFalse(Files.exists(files.scenarioResults().resolve(
                    "string-utils.jsonl.tmp"
            )));
            assertEquals(2, api.closedTaskCount.get());
        }
    }

    @Test
    void rejectsExistingScenarioResultDirectory() throws Exception {
        ScenarioFiles files = scenarioFiles("existing");
        Files.createDirectories(files.scenarioResults());

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerCapabilityRpcMain.main(new String[]{
                        "--scenario-id=" + files.scenarioId(),
                        "--result-dir=" + files.resultRoot()
                })
        );
    }

    @Test
    void removesEmptyResultDirectoryAfterEarlyFailure()
            throws Exception {
        try (FakeRuntimeApi api = FakeRuntimeApi.start(false)) {
            ScenarioFiles files = scenarioFiles("early-failure");
            Files.delete(files.phoneSeed());

            assertThrows(
                    IOException.class,
                    () -> WorkerCapabilityRpcMain.main(
                            arguments(api, files)
                    )
            );

            assertFalse(Files.exists(files.scenarioResults()));
        }
    }

    private ScenarioFiles scenarioFiles(String scenarioId)
            throws IOException {
        Path phoneSeed = temporaryDirectory.resolve(
                scenarioId + "-phone.txt"
        );
        Path stringSeed = temporaryDirectory.resolve(
                scenarioId + "-string.txt"
        );
        List<String> phoneInputs = new ArrayList<>(10);
        List<String> stringInputs = new ArrayList<>(10);
        for (int index = 1; index <= 10; index++) {
            phoneInputs.add("+10000000" + index);
            stringInputs.add("value-" + index);
        }
        Files.write(phoneSeed, phoneInputs, StandardCharsets.UTF_8);
        Files.write(stringSeed, stringInputs, StandardCharsets.UTF_8);
        Path resultRoot = temporaryDirectory.resolve("results");
        return new ScenarioFiles(
                scenarioId,
                phoneSeed,
                stringSeed,
                resultRoot,
                resultRoot.resolve(scenarioId)
        );
    }

    private static String[] arguments(
            FakeRuntimeApi api,
            ScenarioFiles files
    ) {
        return new String[]{
                "--server-base-url=" + api.baseUri(),
                "--scenario-id=" + files.scenarioId(),
                "--phone-seed-path=" + files.phoneSeed(),
                "--string-seed-path=" + files.stringSeed(),
                "--result-dir=" + files.resultRoot(),
                "--wait-timeout-millis=1000",
                "--request-timeout-millis=5000"
        };
    }

    private static List<Map<String, Object>> readJsonLines(Path path)
            throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8)
                .stream()
                .map(Jsons::parseObject)
                .toList();
    }

    private static Set<String> values(
            List<Map<String, Object>> rows,
            String field
    ) {
        Set<String> values = new HashSet<>();
        rows.forEach(row -> values.add((String) row.get(field)));
        return Set.copyOf(values);
    }

    private record ScenarioFiles(
            String scenarioId,
            Path phoneSeed,
            Path stringSeed,
            Path resultRoot,
            Path scenarioResults
    ) {
    }

    private static final class FakeRuntimeApi implements AutoCloseable {
        private final HttpServer server;
        private final boolean failStringCalls;
        private final Set<String> registeredWorkerIds = new HashSet<>();
        private final Set<String> allocatedWorkerIds = new HashSet<>();
        private final AtomicInteger closedTaskCount = new AtomicInteger();

        private FakeRuntimeApi(
                HttpServer server,
                boolean failStringCalls
        ) {
            this.server = server;
            this.failStringCalls = failStringCalls;
        }

        static FakeRuntimeApi start(boolean failStringCalls)
                throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            FakeRuntimeApi api = new FakeRuntimeApi(
                    server,
                    failStringCalls
            );
            server.createContext("/", api::handle);
            server.start();
            return api;
        }

        URI baseUri() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                Map<String, Object> body = readBody(exchange);
                if (path.endsWith("/workers:register")) {
                    handleRegister(exchange, path, body);
                } else if ("/api/v1/tasks".equals(path)) {
                    respond(exchange, 201, Map.of());
                } else if (path.endsWith("/approve")) {
                    respond(exchange, 200, Map.of());
                } else if (path.endsWith("/close")) {
                    closedTaskCount.incrementAndGet();
                    respond(exchange, 200, Map.of());
                } else if (path.endsWith("/items:call")) {
                    handleCall(exchange, path, body);
                } else {
                    respond(exchange, 404, Map.of());
                }
            } catch (RuntimeException error) {
                respond(exchange, 500, Map.of("error", "test failure"));
            }
        }

        private void handleRegister(
                HttpExchange exchange,
                String path,
                Map<String, Object> body
        ) throws IOException {
            String clientWorkerKey = (String) body.get("clientWorkerKey");
            String workerId = UUID.nameUUIDFromBytes(
                    (path + ":" + clientWorkerKey).getBytes(
                            StandardCharsets.UTF_8
                    )
            ).toString();
            registeredWorkerIds.add(workerId);
            respond(exchange, 200, Map.of("workerId", workerId));
        }

        @SuppressWarnings("unchecked")
        private void handleCall(
                HttpExchange exchange,
                String path,
                Map<String, Object> body
        ) throws IOException {
            Map<String, Object> item =
                    (Map<String, Object>) body.get("item");
            String eventCode = (String) item.get("eventCode");
            if (failStringCalls && eventCode.startsWith("string.")) {
                respond(exchange, 500, Map.of("code", 1));
                return;
            }
            Map<String, Object> allocationRule =
                    (Map<String, Object>) item.get("allocationRule");
            Map<String, Object> workerRule =
                    (Map<String, Object>) allocationRule.get("workerId");
            allocatedWorkerIds.add((String) workerRule.get("$eq"));

            Map<String, Object> result = resultFor(eventCode);
            respond(exchange, 200, Map.of(
                    "status", "succeeded",
                    "opaqueResultPayload", Jsons.toJson(result)
            ));
        }

        private static Map<String, Object> resultFor(String eventCode) {
            return switch (eventCode) {
                case "phonenumber.e164" -> Map.of(
                        "valid", true,
                        "e164", "+10000000001"
                );
                case "phonenumber.country" -> Map.of(
                        "valid", true,
                        "countryCallingCode", 1
                );
                case "phonenumber.original-carrier" -> Map.of(
                        "valid", true,
                        "originalCarrier", "test-carrier"
                );
                case "string.md5" -> Map.of(
                        "valid", true,
                        "md5", "test-md5"
                );
                case "string.sha1" -> Map.of(
                        "valid", true,
                        "sha1", "test-sha1"
                );
                case "string.base64.encode" -> Map.of(
                        "valid", true,
                        "base64", "dGVzdA=="
                );
                default -> throw new IllegalArgumentException(
                        "Unknown eventCode: " + eventCode
                );
            };
        }

        private static Map<String, Object> readBody(
                HttpExchange exchange
        ) throws IOException {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            return body.isBlank() ? Map.of() : Jsons.parseObject(body);
        }

        private static void respond(
                HttpExchange exchange,
                int status,
                Map<String, Object> body
        ) throws IOException {
            byte[] encoded = Jsons.toJson(body).getBytes(
                    StandardCharsets.UTF_8
            );
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(status, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
