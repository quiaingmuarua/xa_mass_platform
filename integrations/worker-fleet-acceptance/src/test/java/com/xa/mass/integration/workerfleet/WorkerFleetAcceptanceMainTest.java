package com.xa.mass.integration.workerfleet;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerFleetAcceptanceMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void provesInitialAndRestartRelationshipsWithoutPersistingPayloads()
            throws Exception {
        Fixture fixture = fixture();
        Path initial = temporaryDirectory.resolve("initial.json");
        Path restart = temporaryDirectory.resolve("restart.json");

        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            run(server.baseUri(), fixture, "initial", initial, null);
            server.nextPropertyVersion();
            run(server.baseUri(), fixture, "restart", restart, initial);
        }

        Map<String, Object> initialEvidence = Jsons.parseObject(
                Files.readString(initial, StandardCharsets.UTF_8)
        );
        Map<String, Object> restartEvidence = Jsons.parseObject(
                Files.readString(restart, StandardCharsets.UTF_8)
        );
        assertEquals("succeeded", initialEvidence.get("status"));
        assertEquals("succeeded", restartEvidence.get("status"));
        assertEquals(true, restartEvidence.get("baselineIdentityMatched"));
        String encoded = Files.readString(restart, StandardCharsets.UTF_8);
        assertFalse(encoded.contains("property-version"));
        assertFalse(encoded.contains("opaqueResultPayload"));
        assertFalse(encoded.contains("updatedAtMillis"));
    }

    @Test
    void changedRestartIdentityWritesFailedEvidence() throws Exception {
        Fixture fixture = fixture();
        Path initial = temporaryDirectory.resolve("initial.json");
        Path restart = temporaryDirectory.resolve("restart.json");
        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            run(server.baseUri(), fixture, "initial", initial, null);
            replaceWorkerId(fixture, "group-a", "workers.jsonl:1");

            assertThrows(
                    IllegalStateException.class,
                    () -> run(
                            server.baseUri(),
                            fixture,
                            "restart",
                            restart,
                            initial
                    )
            );
        }

        Map<String, Object> evidence = Jsons.parseObject(Files.readString(
                restart,
                StandardCharsets.UTF_8
        ));
        assertEquals("failed", evidence.get("status"));
        assertEquals(false, evidence.get("baselineIdentityMatched"));
    }

    @Test
    void partialProbeFailsWithoutRetryingTheCommand() throws Exception {
        Fixture fixture = fixture();
        Path evidence = temporaryDirectory.resolve("failed.json");
        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            server.dropOneProbeResult();
            assertThrows(
                    IllegalStateException.class,
                    () -> run(
                            server.baseUri(),
                            fixture,
                            "initial",
                            evidence,
                            null
                    )
            );
            assertEquals(1, server.probeCalls());
        }
        assertEquals(
                "failed",
                Jsons.parseObject(Files.readString(evidence)).get("status")
        );
    }

    @Test
    void disconnectedNetworkAndExtraIdentityFailWithSafeDifferences()
            throws Exception {
        Fixture fixture = fixture();
        Path evidence = temporaryDirectory.resolve("network-failed.json");
        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            server.breakNetworkObservation();
            assertThrows(
                    IllegalStateException.class,
                    () -> run(
                            server.baseUri(),
                            fixture,
                            "initial",
                            evidence,
                            null
                    )
            );
        }
        String encoded = Files.readString(evidence, StandardCharsets.UTF_8);
        assertTrue(encoded.contains("network.connected-identities"));
        assertTrue(encoded.contains("unexpected-network-worker"));
        assertFalse(encoded.contains("opaqueResultPayload"));
    }

    @Test
    void wrongProbeOutcomeAndPropertiesMismatchFailWithoutPayloadEvidence()
            throws Exception {
        Fixture fixture = fixture();
        Path probeEvidence = temporaryDirectory.resolve("probe-failed.json");
        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            server.wrongProbeOutcome();
            assertThrows(
                    IllegalStateException.class,
                    () -> run(
                            server.baseUri(),
                            fixture,
                            "initial",
                            probeEvidence,
                            null
                    )
            );
            assertEquals(1, server.probeCalls());
        }
        assertFalse(Files.readString(probeEvidence).contains(
                "reachable"
        ));

        Path propertiesEvidence = temporaryDirectory.resolve(
                "properties-failed.json"
        );
        try (FakeFleetServer server = FakeFleetServer.start(
                fixture.workerIds()
        )) {
            server.mismatchProperties();
            assertThrows(
                    IllegalStateException.class,
                    () -> run(
                            server.baseUri(),
                            fixture,
                            "initial",
                            propertiesEvidence,
                            null
                    )
            );
        }
        String encoded = Files.readString(propertiesEvidence);
        assertTrue(encoded.contains(
                "properties.adapter-cache-identities"
        ));
        assertFalse(encoded.contains("property-version"));
    }

    private Fixture fixture() throws Exception {
        Path lab = temporaryDirectory.resolve("data").resolve(
                "scenario-workers"
        );
        Files.createDirectories(lab);
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("group-a", List.of("workers.jsonl:1", "workers.jsonl:2"));
        keys.put("group-b", List.of("workers.jsonl:1", "workers.jsonl:2"));
        Map<String, Map<String, String>> workerIds = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : keys.entrySet()) {
            Path directory = Files.createDirectories(lab.resolve(
                    group.getKey()
            ));
            Map<String, String> groupIds = new LinkedHashMap<>();
            for (String clientKey : group.getValue()) {
                String workerId = UUID.randomUUID().toString();
                groupIds.put(clientKey, workerId);
            }
            writeStates(
                    directory.resolve("workers.jsonl"),
                    group.getValue().size()
            );
            workerIds.put(group.getKey(), groupIds);
        }
        Path spec = temporaryDirectory.resolve("fleet-spec.json");
        Map<String, Object> encodedGroups = new LinkedHashMap<>();
        keys.forEach((groupId, clientKeys) -> encodedGroups.put(
                groupId,
                Map.of("labWorkerKeys", clientKeys)
        ));
        Files.writeString(
                spec,
                Jsons.toJson(Map.of(
                        "endpointManagerId", "adapter",
                        "groups", encodedGroups
                )),
                StandardCharsets.UTF_8
        );
        return new Fixture(lab, spec, keys, workerIds);
    }

    private static void run(
            URI serverBaseUrl,
            Fixture fixture,
            String phase,
            Path evidence,
            Path baseline
    ) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(
                "--phase=" + phase,
                "--proof-id=proof",
                "--server-base-url=" + serverBaseUrl,
                "--fleet-spec=" + fixture.spec(),
                "--scenario-worker-lab-root=" + fixture.lab(),
                "--evidence-file=" + evidence,
                "--maximum-wait-millis=1000",
                "--request-timeout-millis=5000"
        ));
        if (baseline != null) {
            arguments.add("--baseline-file=" + baseline);
        }
        WorkerFleetAcceptanceMain.main(arguments.toArray(String[]::new));
    }

    private static void replaceWorkerId(
            Fixture fixture,
            String groupId,
            String clientKey
    ) throws Exception {
        String workerId = UUID.randomUUID().toString();
        fixture.workerIds().get(groupId).put(clientKey, workerId);
    }

    private static void writeStates(Path path, int count)
            throws Exception {
        List<String> states = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            states.add(Jsons.toJson(Map.of(
                    "schemaVersion", 2,
                    "workerProperties", Map.of(
                            "labInventoryKey", path.getFileName().toString(),
                            "labInventoryLine", index + 1,
                            "dynamic", true
                    )
            )));
        }
        Files.writeString(
                path,
                String.join("\n", states) + "\n",
                StandardCharsets.UTF_8
        );
    }

    private record Fixture(
            Path lab,
            Path spec,
            Map<String, List<String>> clientKeys,
            Map<String, Map<String, String>> workerIds
    ) {
    }

    private static final class FakeFleetServer implements AutoCloseable {

        private final HttpServer server;
        private final Map<String, Map<String, String>> workersByGroup;
        private final Map<String, String> groupByWorkerId =
                new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> latestProperties =
                new LinkedHashMap<>();
        private int propertyVersion = 1;
        private int probeCalls;
        private boolean dropOneProbeResult;
        private boolean breakNetworkObservation;
        private boolean wrongProbeOutcome;
        private boolean mismatchProperties;

        private FakeFleetServer(
                HttpServer server,
                Map<String, Map<String, String>> workers
        ) {
            this.server = server;
            this.workersByGroup = workers;
            workers.forEach((groupId, group) -> group.values().forEach(
                    workerId -> groupByWorkerId.put(workerId, groupId)
            ));
        }

        static FakeFleetServer start(
                Map<String, Map<String, String>> workers
        ) throws IOException {
            HttpServer http = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            FakeFleetServer fake = new FakeFleetServer(http, workers);
            http.createContext("/api/v1", fake::handle);
            http.start();
            return fake;
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.contains("/runtime-view/worker-groups/")
                        && path.endsWith("/workers:preview")) {
                    workerPreview(exchange, path);
                } else if (path.endsWith("/workers:network-observe")) {
                    network(exchange);
                } else if (path.endsWith("/direct-calls")) {
                    directCall(exchange);
                } else {
                    respond(exchange, 404, Map.of());
                }
            } catch (RuntimeException error) {
                respond(exchange, 500, Map.of("error", "fixture failure"));
            }
        }

        private void workerPreview(HttpExchange exchange, String path)
                throws IOException {
            String marker = "/runtime-view/worker-groups/";
            int start = path.indexOf(marker) + marker.length();
            String groupId = path.substring(
                    start,
                    path.length() - "/workers:preview".length()
            );
            Map<String, String> identities = workersByGroup.get(groupId);
            if (identities == null) {
                respond(exchange, 404, Map.of());
                return;
            }
            List<Map<String, Object>> workers = identities.entrySet()
                    .stream()
                    .map(entry -> Map.<String, Object>of(
                            "workerId", entry.getValue(),
                            "workerGroupId", groupId,
                            "workerProperties", labProperties(entry.getKey())
                    ))
                    .toList();
            respond(exchange, 200, Map.of(
                    "sampleLimit", 100,
                    "sampledCount", workers.size(),
                    "returnedCount", workers.size(),
                    "unreadableCount", 0,
                    "workers", workers
            ));
        }

        private static Map<String, Object> labProperties(
                String labWorkerKey
        ) {
            int separator = labWorkerKey.lastIndexOf(':');
            return Map.of(
                    "labInventoryKey",
                    labWorkerKey.substring(0, separator),
                    "labInventoryLine",
                    Long.parseLong(labWorkerKey.substring(separator + 1))
            );
        }

        private void network(HttpExchange exchange) throws IOException {
            Map<String, Object> request = request(exchange);
            List<?> workerIds = (List<?>) request.get("workerIds");
            Map<String, String> states = new LinkedHashMap<>();
            workerIds.forEach(workerId -> states.put(
                    (String) workerId,
                    "connected"
            ));
            if (breakNetworkObservation) {
                states.put((String) workerIds.get(0), "disconnected");
                states.put("unexpected-network-worker", "connected");
            }
            respond(exchange, 200, Map.of("statesByWorkerId", states));
        }

        private void directCall(HttpExchange exchange) throws IOException {
            Map<String, Object> request = request(exchange);
            String event = (String) request.get("messageType");
            Object rawWorkers = request.get("workerPayloads");
            if (rawWorkers instanceof Map<?, ?> workerPayloads) {
                workerCall(exchange, event, workerPayloads);
            } else {
                adapterCall(exchange, request);
            }
        }

        private void workerCall(
                HttpExchange exchange,
                String event,
                Map<?, ?> workerPayloads
        ) throws IOException {
            Map<String, Object> results = new LinkedHashMap<>();
            int index = 0;
            if (event.equals("platform.worker.probe")) {
                probeCalls++;
            }
            for (Object rawWorkerId : workerPayloads.keySet()) {
                String workerId = (String) rawWorkerId;
                if (event.equals("platform.worker.probe")) {
                    if (dropOneProbeResult && index++ == 0) {
                        continue;
                    }
                    if (wrongProbeOutcome && index++ == 0) {
                        results.put(
                                workerId,
                                observed("500", "{\"reachable\":true}")
                        );
                    } else {
                        results.put(
                                workerId,
                                observed("{\"reachable\":true}")
                        );
                    }
                } else if (event.equals(
                        "platform.worker.properties.snapshot"
                )) {
                    Map<String, Object> properties = Map.of(
                            "worker", workerId,
                            "property-version", propertyVersion
                    );
                    latestProperties.put(workerId, properties);
                    results.put(workerId, observed(Jsons.toJson(Map.of(
                            "properties", properties
                    ))));
                }
            }
            respond(exchange, 200, directResponse(results));
        }

        private void adapterCall(
                HttpExchange exchange,
                Map<String, Object> request
        ) throws IOException {
            String payload = (String) request.get("opaquePayload");
            List<?> workerIds = (List<?>) Jsons.parseObject(payload).get(
                    "workerIds"
            );
            Map<String, Object> observations = new LinkedHashMap<>();
            workerIds.forEach(rawWorkerId -> {
                String workerId = (String) rawWorkerId;
                observations.put(workerId, Map.of(
                        "updatedAtMillis", 100L + propertyVersion,
                        "properties",
                        mismatchProperties
                                ? Map.of("mismatch", true)
                                : latestProperties.get(workerId)
                ));
            });
            Map<String, Object> results = Map.of(
                    "adapter",
                    observed(Jsons.toJson(Map.of(
                            "propertiesByWorkerId", observations
                    )))
            );
            respond(exchange, 200, directResponse(results));
        }

        private static Map<String, Object> directResponse(
                Map<String, Object> results
        ) {
            return Map.of(
                    "directCallId", UUID.randomUUID().toString(),
                    "status", results.isEmpty() ? "partial" : "observed",
                    "results", results
            );
        }

        private static Map<String, Object> observed(String payload) {
            return observed("200", payload);
        }

        private static Map<String, Object> observed(
                String outcomeCode,
                String payload
        ) {
            return Map.of(
                    "status", "observed",
                    "outcomeCode", outcomeCode,
                    "opaqueResultPayload", payload
            );
        }

        private static Map<String, Object> request(HttpExchange exchange)
                throws IOException {
            return Jsons.parseObject(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
        }

        private static void respond(
                HttpExchange exchange,
                int status,
                Map<String, Object> body
        ) throws IOException {
            byte[] encoded = Jsons.toJson(body).getBytes(
                    StandardCharsets.UTF_8
            );
            exchange.sendResponseHeaders(status, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        }

        URI baseUri() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
        }

        void nextPropertyVersion() {
            propertyVersion++;
        }

        void dropOneProbeResult() {
            dropOneProbeResult = true;
        }

        void breakNetworkObservation() {
            breakNetworkObservation = true;
        }

        void wrongProbeOutcome() {
            wrongProbeOutcome = true;
        }

        void mismatchProperties() {
            mismatchProperties = true;
        }

        int probeCalls() {
            return probeCalls;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
