package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaneWorldTest {

    @Test
    void expandedInventoryUsesCompleteIdentityPagesWithinThePublicLimit() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var page = Jsons.parseArray(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (page.isEmpty() || page.size() > 100) {
                exchange.sendResponseHeaders(400, -1);
            } else {
                requests.incrementAndGet();
                var states = new LinkedHashMap<String, Object>();
                page.forEach(id -> states.put((String) id, "hot-score-overdue"));
                byte[] bytes = Jsons.toJson(Map.of("statesByWorkerId", states)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            assertThat(LaneWorld.observeScheduling(api, "perf-a", ids("w", 4000))).hasSize(4000);
            assertThat(requests).hasValue(40);
        } finally { server.stop(0); }
    }

    @Test
    void quietCountsOnlyStatesThatDifferFromTheIdleOne() {
        var counts = new LinkedHashMap<String, Long>();
        LaneWorld.count(counts, Map.of("w1", "hot-score-overdue", "w2", "held-hot", "w3", "recovery", "w4", "held-hot"),
                "hot-score-overdue", "scheduling:");
        LaneWorld.count(counts, Map.of("w1", "connected", "w2", "disconnected"), "connected", "network:");

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "scheduling:held-hot", 2L, "scheduling:recovery", 1L, "network:disconnected", 1L));
    }

    @Test
    void bootstrapReadinessAcceptsHeldOrIdleHotButQuiesceDoesNot() {
        assertThat(LaneWorld.allIn(Map.of("w1", "held-hot", "w2", "hot-score-overdue"),
                Set.of("held-hot", "hot-score-overdue"))).isTrue();
        var counts = new LinkedHashMap<String, Long>();
        LaneWorld.count(counts, Map.of("w1", "held-hot"), "hot-score-overdue", "scheduling:");
        assertThat(counts).isNotEmpty();
    }

    @Test
    void quiesceReadsTheCompleteBootstrapWorld(@TempDir Path folder) throws Exception {
        var groups = new LinkedHashMap<String, Object>();
        for (String group : LaneWorld.GROUPS)
            groups.put(group, Map.of("managedTaskId", "task-" + group, "workerIds", ids(group, LaneWorld.WORKERS_PER_GROUP)));
        Path complete = folder.resolve("bootstrap.json");
        Files.writeString(complete, Jsons.toJson(Map.of("groups", groups)));

        var world = LaneWorld.readWorld(complete);
        assertThat(world.keySet()).containsExactlyElementsOf(LaneWorld.GROUPS);
        assertThat(world.get("perf-b")).hasSize(LaneWorld.WORKERS_PER_GROUP).first().isEqualTo("perf-b-0");

        groups.put("perf-b", Map.of("managedTaskId", "task-perf-b", "workerIds", ids("perf-b", 999)));
        Path partial = folder.resolve("partial.json");
        Files.writeString(partial, Jsons.toJson(Map.of("groups", groups)));
        assertThatThrownBy(() -> LaneWorld.readWorld(partial)).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test
    void lanePhasesRejectLegacyCaseSelection() {
        assertThatThrownBy(() -> WorkerCallPerformanceMain.main(new String[] {"--phase=bootstrap", "--case=any-100"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> ids(String group, int count) {
        return IntStream.range(0, count).mapToObj(i -> group + "-" + i).toList();
    }
}
