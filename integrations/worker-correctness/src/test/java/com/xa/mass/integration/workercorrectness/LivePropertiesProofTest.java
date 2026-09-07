package com.xa.mass.integration.workercorrectness;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LivePropertiesProofTest {
    @TempDir Path directory;

    @Test
    void rejectsHybridAndLostDeltaSnapshotsWhileAllowingSkippedIntermediateStates() {
        Map<String, String> baseline = Map.of("sequence", "0", "mirror", "0", "empty", "");
        Map<String, String> first = Map.of("sequence", "1", "mirror", "1", "empty", "", "delta-1", "1");
        Map<String, String> last = Map.of("sequence", "2", "mirror", "2", "empty", "", "delta-1", "1", "delta-2", "2");
        List<Map<String, String>> committed = List.of(baseline, first, last);
        assertDoesNotThrow(() -> LivePropertiesProof.requireCommitted(baseline, committed));
        assertDoesNotThrow(() -> LivePropertiesProof.requireCommitted(last, committed));
        Map<String, String> lost = new LinkedHashMap<>(last);
        lost.remove("delta-1");
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.requireCommitted(lost, committed));
        Map<String, String> hybrid = new LinkedHashMap<>(last);
        hybrid.put("mirror", "1");
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.requireCommitted(hybrid, committed));
        hybrid.put("mirror", "2");
        hybrid.remove("empty");
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.requireCommitted(hybrid, committed));
    }

    @Test
    void replacementDropsOmittedKeysAndDigestIsOrderIndependent() {
        Map<String, String> full = Map.of("coordinate", "1", "delta", "kept");
        Map<String, String> replacement = Map.of("coordinate", "1");
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.requireCommitted(full, List.of(replacement)));
        assertNotEquals(LivePropertiesProof.digest(full), LivePropertiesProof.digest(replacement));
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("delta", "kept");
        reordered.put("coordinate", "1");
        assertEquals(LivePropertiesProof.digest(full), LivePropertiesProof.digest(reordered));
    }

    @Test
    void deadlineIncludesMutationRequestTimeAndNeverRoundsDownAnOverrun() {
        long sent = 123;
        long budget = TimeUnit.SECONDS.toNanos(5);
        assertEquals(5_000, LivePropertiesProof.observedWithin(sent, sent + budget));
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.observedWithin(sent, sent + budget + 1));
        assertThrows(IllegalStateException.class, () -> LivePropertiesProof.observedWithin(sent, sent - 1));
    }

    @Test
    void labRunOracleUsesThePublicIdentityAndStateFields() {
        Map<String, Object> lab = new LinkedHashMap<>(Map.of("workerGroupId", "group",
                "labWorkerKey", "inventory:1", "workerId", "id", "desiredState", "RUNNING",
                "runtimeState", "RUNNING"));
        assertDoesNotThrow(() -> LivePropertiesProof.requireLabRun(lab, "group", "inventory:1", "id"));
        lab.put("desiredState", "STOPPED");
        assertThrows(IllegalStateException.class,
                () -> LivePropertiesProof.requireLabRun(lab, "group", "inventory:1", "id"));
        lab.put("desiredState", "RUNNING");
        lab.put("workerId", "another");
        assertThrows(IllegalStateException.class,
                () -> LivePropertiesProof.requireLabRun(lab, "group", "inventory:1", "id"));
    }

    @Test
    void httpPreflightUsesPublicWireShapesAndUnacceptedMutationFailsWithoutRetry() throws Exception {
        CorrectnessSpec spec = CorrectnessSpec.load(Path.of("correctness-spec.json"));
        Map<String, Object> groups = new LinkedHashMap<>();
        spec.labWorkerKeysByGroup().forEach((group, keys) -> {
            Map<String, String> ids = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) ids.put(keys.get(i), group + "-" + (i + 1));
            groups.put(group, Map.of("workerIdsByLabWorkerKey", ids));
        });
        Path baseline = directory.resolve("initial.json");
        Files.writeString(baseline, Jsons.toJson(Map.of("status", "succeeded", "phase", "initial",
                "proofId", "proof", "endpointManagerId", spec.endpointManagerId(), "groups", groups)));
        AtomicInteger mutations = new AtomicInteger();
        List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, ?> response;
            if (path.endsWith(":properties")) {
                mutations.incrementAndGet();
                response = Map.of("persisted", true, "sendAccepted", false);
            } else if (path.startsWith("/lab/")) {
                String[] parts = path.split("/");
                int line = Integer.parseInt(parts[5].split(":")[1]);
                response = Map.of("workerGroupId", parts[4], "labWorkerKey", parts[5],
                        "workerId", parts[4] + "-" + line, "desiredState", "RUNNING",
                        "runtimeState", "RUNNING", "workerProperties", fixtureProperties(line));
            } else if (path.endsWith("/direct-calls")) {
                Map<String, Object> request = Jsons.parseObject(body);
                assertEquals(1_000L, request.get("waitTimeoutMillis"));
                Map<String, Object> snapshot = new LinkedHashMap<>();
                for (Object id : (List<?>) Jsons.parseObject((String) request.get("opaquePayload")).get("workerIds")) {
                    String workerId = (String) id;
                    int line = Integer.parseInt(workerId.substring(workerId.lastIndexOf('-') + 1));
                    snapshot.put(workerId, Map.of("updatedAtMillis", 1, "properties", fixtureProperties(line)));
                }
                response = Map.of("status", "observed", "results", Map.of(spec.endpointManagerId(),
                        Map.of("status", "observed", "outcomeCode", "200", "opaqueResultPayload",
                                Jsons.toJson(Map.of("propertiesByWorkerId", snapshot)))));
            } else if (path.endsWith("/workers:preview")) {
                String group = path.split("/")[5];
                List<Map<String, Object>> views = new ArrayList<>();
                for (int i = 1; i <= 50; i++) {
                    views.add(Map.of("workerId", group + "-" + i, "workerGroupId", group,
                            "endpointManagerId", spec.endpointManagerId(), "workerProperties", fixtureProperties(i)));
                }
                response = Map.of("unreadableCount", 0, "workers", views);
            } else if (path.endsWith("/workers:network-observe")) {
                Map<String, String> states = new LinkedHashMap<>();
                for (Object id : Jsons.parseArray(body)) states.put((String) id, "connected");
                response = Map.of("statesByWorkerId", states);
            } else {
                throw new AssertionError("Unexpected proof HTTP path");
            }
            byte[] encoded = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, encoded.length);
            exchange.getResponseBody().write(encoded);
            exchange.close();
        });
        server.start();
        try {
            Path output = directory.resolve("live.json");
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            CorrectnessOptions options = CorrectnessOptions.parse(new String[]{"--phase=live-properties",
                    "--proof-id=proof", "--baseline-file=" + baseline, "--evidence-file=" + output,
                    "--lab-base-url=" + baseUrl, "--server-base-url=" + baseUrl});
            assertThrows(IllegalStateException.class, () -> LivePropertiesProof.execute(options));
            Map<String, Object> result = Jsons.parseObject(Files.readString(output));
            assertEquals("mutation-not-accepted", result.get("failure"));
            assertEquals("replacement-1", result.get("stage"));
            assertEquals(1, mutations.get());
            assertEquals(1L, result.get("persistedCount"));
            assertEquals(0L, result.get("sendAcceptedCount"));
            assertTrue(paths.stream().noneMatch(path -> path.contains("prepare")
                    || path.endsWith(":start") || path.endsWith(":stop")));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> fixtureProperties(int line) {
        return Map.of("labInventoryKey", "workers-000.jsonl", "labInventoryLine", Integer.toString(line));
    }

    @Test
    void evidenceContainsOnlyDigestsAndCannotPassWithoutRunnerAudit() throws Exception {
        LivePropertiesEvidence evidence = new LivePropertiesEvidence();
        String privateValue = "private-property-value";
        evidence.checkpoint("check", 1, LivePropertiesProof.digest(Map.of("sensitive", privateValue)), 3, 7, 2);
        Path output = directory.resolve("evidence.json");
        evidence.write(output, "proof", "adapter");
        String encoded = Files.readString(output);
        assertTrue(encoded.contains("pending-runner-audit"));
        assertFalse(encoded.contains(privateValue));
        assertFalse(encoded.contains("sensitive"));
        assertFalse(encoded.contains("opaqueResultPayload"));
        assertFalse(encoded.contains("propertiesByWorkerId"));
    }
}
