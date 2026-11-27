package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LaneReadinessTest {
    @Test
    void accumulatesOnlyCurrentNonemptyPropertiesAndSamplesBothUnfinishedGroups() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            fixture.previews("a", List.of(fact("historical-a", true), fact("a-0000", true),
                    fact("a-0000", true), fact("a-0001", false)), List.of(fact("a-0001", true)));
            fixture.previews("b", List.of(fact("historical-b", false), fact("b-0000", true),
                    fact("b-0001", false)), List.of(fact("b-0001", true)));
            var readiness = new LaneWorld.Readiness();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(fixture.previewRequests).containsEntry("a", 1).containsEntry("b", 1);
            assertThat(fixture.liveRequests).isZero();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isEqualTo(fixture.world);
            assertThat(fixture.previewRequests).containsEntry("a", 2).containsEntry("b", 2);
        }
    }

    @Test
    void historicalEmptyAndDuplicateRowsCannotCompleteMissingCoverage() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            fixture.previews("a", List.of(fact("historical-a", true), fact("a-0000", true),
                    fact("a-0000", true), fact("a-0001", false)));
            var readiness = new LaneWorld.Readiness();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(fixture.previewRequests).containsEntry("a", 2).containsEntry("b", 1);
            assertThat(fixture.liveRequests).isZero();
        }
    }

    @Test
    void freshSourceWorldPassesWithOneCompletePreviewPerGroup() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            var bootstrap = LaneWorld.bootstrap(fixture.api, fixture.coordinates);
            assertThat(bootstrap).containsEntry("propertiesObservation", "complete-known-fixture")
                    .containsEntry("workersPerGroup", 2);
            assertThat(fixture.previewRequests).containsEntry("a", 1).containsEntry("b", 1);
            assertThat(fixture.liveRequests).isEqualTo(4);
        }
    }

    @Test
    void propertiesCoverageNeverBypassesCurrentNetworkHotOrLabRunningGates() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            var readiness = new LaneWorld.Readiness();
            fixture.network.put("a-0001", "disconnected");
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            fixture.network.clear();
            fixture.scheduling.put("b-0001", "recovery");
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            fixture.scheduling.put("b-0001", "held-hot");
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isEqualTo(fixture.world);
            fixture.runtimeStates.put("a-0000", "STOPPED");
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(fixture.previewRequests).containsEntry("a", 1).containsEntry("b", 1);
        }
    }

    @Test
    void changedLabIdentitySetDiscardsCoverageForAllGroups() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            fixture.previews("b", List.of(fact("b-0000", true)), List.of(fact("b-0001", true)),
                    List.of(fact("b-0000", true), fact("b-0001", true)));
            var readiness = new LaneWorld.Readiness();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            fixture.world.put("a", List.of("a-0000", "a-new"));
            fixture.previews("a", List.of(fact("a-new", true)), List.of(fact("a-0000", true)));
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isEqualTo(fixture.world);
            assertThat(fixture.previewRequests).containsEntry("a", 3).containsEntry("b", 3);
        }
    }

    @Test
    void largerWorldUsesStableThousandPropertiesTargetsButChecksEveryLiveIdentity() throws Exception {
        try (var fixture = new RuntimeFixture(1001)) {
            for (String group : fixture.coordinates.groups())
                fixture.previews(group, fixture.world.get(group).subList(0, 1000).stream().map(id -> fact(id, true)).toList());
            fixture.scheduling.put("a-1000", "cold");
            assertThat(new LaneWorld.Readiness().observe(fixture.api, fixture.coordinates)).isNull();
            fixture.scheduling.clear();
            var bootstrap = LaneWorld.bootstrap(fixture.api, fixture.coordinates);
            assertThat(bootstrap).containsEntry("propertiesObservation", "bounded-sample")
                    .containsEntry("workersPerGroup", 1001);
            assertThat((List<?>) CallApi.object(CallApi.object(bootstrap.get("groups")).get("a")).get("workerIds"))
                    .hasSize(1001);
        }
    }

    @Test
    void temporarilyUnavailablePreviewDoesNotStarveTheOtherGroup() throws Exception {
        try (var fixture = new RuntimeFixture(2)) {
            fixture.unavailablePreviews.put("a", 1);
            var readiness = new LaneWorld.Readiness();
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isNull();
            assertThat(fixture.previewRequests).containsEntry("a", 1).containsEntry("b", 1);
            assertThat(readiness.observe(fixture.api, fixture.coordinates)).isEqualTo(fixture.world);
            assertThat(fixture.previewRequests).containsEntry("a", 2).containsEntry("b", 1);
        }
    }

    private static Map<String, Object> fact(String id, boolean present) {
        return Map.of("workerId", id, "workerProperties", present ? Map.of("runtime", "java") : Map.of());
    }

    /** Only HTTP responses are scripted; the real CallApi and readiness algorithm perform every read. */
    private static final class RuntimeFixture implements AutoCloseable {
        final LaneWorld.Coordinates coordinates;
        final Map<String, List<String>> world = new LinkedHashMap<>();
        final Map<String, String> network = new LinkedHashMap<>();
        final Map<String, String> scheduling = new LinkedHashMap<>();
        final Map<String, String> runtimeStates = new LinkedHashMap<>();
        final Map<String, Integer> previewRequests = new LinkedHashMap<>();
        final Map<String, Integer> unavailablePreviews = new LinkedHashMap<>();
        final Map<String, ArrayDeque<List<Map<String, Object>>>> previews = new LinkedHashMap<>();
        final HttpServer server;
        final CallApi api;
        int liveRequests;

        RuntimeFixture(int count) throws Exception {
            coordinates = new LaneWorld.Coordinates(List.of("a", "b"), "project", "endpoint", count, 15000, 100, null);
            for (String group : coordinates.groups()) {
                var ids = IntStream.range(0, count).mapToObj(i -> group + "-" + String.format("%04d", i)).toList();
                world.put(group, ids);
                previews(group, ids.subList(0, Math.min(1000, count)).stream().map(id -> fact(id, true)).toList());
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Object response;
                int status = 200;
                if (path.equals("/lab/v1/workers")) {
                    var workers = new ArrayList<Map<String, Object>>();
                    world.forEach((group, ids) -> ids.forEach(id -> workers.add(Map.of("workerGroupId", group,
                            "workerId", id, "runtimeState", runtimeStates.getOrDefault(id, "RUNNING")))));
                    response = Map.of("workers", workers);
                } else if (path.equals("/api/v1/tasks/rpc")) {
                    String group = (String) Jsons.parseObject(input).get("workerGroupId");
                    response = Map.of("taskId", coordinates.rpcTaskId(group));
                } else if (path.endsWith(":register")) {
                    response = Map.of("workerGroupId", path.substring(path.lastIndexOf('/') + 1, path.indexOf(":register")));
                } else if (path.endsWith("/workers:preview")) {
                    String group = path.split("/")[5];
                    previewRequests.merge(group, 1, Integer::sum);
                    if (!"1000".equals(input)) throw new AssertionError("Preview must remain bounded by the existing API limit");
                    if (unavailablePreviews.getOrDefault(group, 0) > 0) {
                        unavailablePreviews.compute(group, (key, value) -> value - 1);
                        status = 503;
                        response = Map.of("code", 1);
                    } else {
                        var pages = previews.get(group);
                        response = Map.of("workers", pages.size() > 1 ? pages.removeFirst() : pages.getFirst());
                    }
                } else {
                    liveRequests++;
                    var states = new LinkedHashMap<String, Object>();
                    var page = Jsons.parseArray(input);
                    if (page.isEmpty() || page.size() > 100) throw new AssertionError("Live observation exceeded its API page limit");
                    for (Object value : page) {
                        String id = (String) value;
                        states.put(id, path.endsWith("/workers:network-observe")
                                ? network.getOrDefault(id, "connected") : scheduling.getOrDefault(id, "hot-score-overdue"));
                    }
                    response = Map.of("statesByWorkerId", states);
                }
                byte[] bytes = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            api = new CallApi(base, base, Map.of(), coordinates.endpoint());
        }

        @SafeVarargs
        final void previews(String group, List<Map<String, Object>>... pages) {
            previews.put(group, new ArrayDeque<>(List.of(pages)));
        }

        @Override public void close() throws Exception {
            try { api.close(); }
            finally { server.stop(0); }
        }
    }
}
