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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerCapabilityRpcMainTest {

    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesSixtyGroupScopedResultsWithoutInternalCoordinates()
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
            assertTrue(phone.stream().allMatch(row ->
                    PHONE_GROUP.equals(row.get("workerGroupId"))
            ));
            assertTrue(strings.stream().allMatch(row ->
                    STRING_GROUP.equals(row.get("workerGroupId"))
            ));
            assertTrue(phone.stream().allMatch(
                    WorkerCapabilityRpcMainTest::hasOnlyPublicResultFields
            ));
            assertTrue(strings.stream().allMatch(
                    WorkerCapabilityRpcMainTest::hasOnlyPublicResultFields
            ));
            assertEquals(60, api.callCount.get());
            assertEquals(
                    Set.of(PHONE_GROUP, STRING_GROUP),
                    api.calledWorkerGroups
            );
            assertTrue(api.allocationRulesWereEmpty);
            assertFalse(api.sawTaskOrWorkerCoordinate);
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
        Path labRoot = temporaryDirectory.resolve(
                "data/scenario-workers"
        );
        writeWorkerLab(labRoot, PHONE_GROUP, "phone-worker-");
        writeWorkerLab(labRoot, STRING_GROUP, "string-worker-");
        return new ScenarioFiles(
                scenarioId,
                phoneSeed,
                stringSeed,
                resultRoot,
                resultRoot.resolve(scenarioId),
                labRoot
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
                "--scenario-worker-lab-root=" + files.labRoot(),
                "--wait-timeout-millis=1000",
                "--request-timeout-millis=5000"
        };
    }

    private static boolean hasOnlyPublicResultFields(
            Map<String, Object> row
    ) {
        return row.keySet().equals(Set.of(
                "workerGroupId",
                "messageId",
                "eventCode",
                "input",
                "result"
        ));
    }

    private static List<Map<String, Object>> readJsonLines(Path path)
            throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8)
                .stream()
                .map(Jsons::parseObject)
                .toList();
    }

    private static void writeWorkerLab(
            Path labRoot,
            String workerGroupId,
            String workerKeyPrefix
    ) throws IOException {
        Path groupDirectory = labRoot.resolve(workerGroupId);
        Files.createDirectories(groupDirectory);
        for (int index = 1; index <= 10; index++) {
            String clientWorkerKey = workerKeyPrefix
                    + "%03d".formatted(index);
            String workerId = UUID.nameUUIDFromBytes(
                    (workerGroupId + ":" + clientWorkerKey).getBytes(
                            StandardCharsets.UTF_8
                    )
            ).toString();
            Files.writeString(
                    groupDirectory.resolve(clientWorkerKey + ".json"),
                    Jsons.toJson(Map.of(
                            "schemaVersion", 1,
                            "workerId", workerId
                    )),
                    StandardCharsets.UTF_8
            );
        }
    }

    private record ScenarioFiles(
            String scenarioId,
            Path phoneSeed,
            Path stringSeed,
            Path resultRoot,
            Path scenarioResults,
            Path labRoot
    ) {
    }

    private static final class FakeRuntimeApi implements AutoCloseable {
        private final HttpServer server;
        private final boolean failStringCalls;
        private final Set<String> calledWorkerGroups =
                ConcurrentHashMap.newKeySet();
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile boolean allocationRulesWereEmpty = true;
        private volatile boolean sawTaskOrWorkerCoordinate;

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

        @SuppressWarnings("unchecked")
        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith("/api/v1/worker-groups/")
                        || !path.endsWith("/items:call")) {
                    respond(exchange, 404, Map.of());
                    return;
                }
                String workerGroupId = path.substring(
                        "/api/v1/worker-groups/".length(),
                        path.length() - "/items:call".length()
                );
                Map<String, Object> body = readBody(exchange);
                Map<String, Object> item =
                        (Map<String, Object>) body.get("item");
                String eventCode = (String) item.get("eventCode");
                calledWorkerGroups.add(workerGroupId);
                callCount.incrementAndGet();
                allocationRulesWereEmpty &= Map.of().equals(
                        item.get("allocationRule")
                );
                sawTaskOrWorkerCoordinate |= body.containsKey("taskId")
                        || body.containsKey("workerId")
                        || item.containsKey("taskId")
                        || item.containsKey("workerId")
                        || item.containsKey("workerGroupId");
                if (failStringCalls && eventCode.startsWith("string.")) {
                    respond(exchange, 500, Map.of("code", 1));
                    return;
                }
                respond(exchange, 200, Map.of(
                        "status", "succeeded",
                        "messageId", item.get("messageId"),
                        "opaqueResultPayload", Jsons.toJson(
                                resultFor(eventCode)
                        )
                ));
            } catch (RuntimeException error) {
                respond(exchange, 500, Map.of("error", "test failure"));
            }
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
