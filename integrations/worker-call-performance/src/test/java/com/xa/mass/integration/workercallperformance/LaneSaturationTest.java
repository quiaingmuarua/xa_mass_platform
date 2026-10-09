package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LaneSaturationTest {

    @Test
    void saturationCasesAreNamedAndRoutedSeparatelyFromOpenLoopCases() {
        assertThat(LaneSaturation.parse("sat-task-any")).isEqualTo(LaneSaturation.Path.TASK_ANY);
        assertThat(LaneSaturation.parse("sat-task-targeted")).isEqualTo(LaneSaturation.Path.TASK_TARGETED);
        assertThat(LaneSaturation.handles("sat-task-any")).isTrue();
        assertThat(LaneSaturation.handles("task-any-500")).isFalse();
        assertThatThrownBy(() -> LaneSaturation.parse("sat-direct")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seededItemsUseTheAnyPoolOrRotateTargetWorkersWithALongTtl() {
        var ids = List.of("w0", "w1", "w2");
        var any = LaneSaturation.items(LaneSaturation.Path.TASK_ANY, "perf-a", ids, "p", 200);
        var targeted = LaneSaturation.items(LaneSaturation.Path.TASK_TARGETED, "perf-b", ids, "p", 0);

        assertThat(any).hasSize(LaneSaturation.APPEND_BATCH);
        assertThat(any.getFirst()).containsEntry("messageId", "p-perf-a-200").containsEntry("ttlMillis", 900_000L)
                .containsEntry("workerSelector", Map.of("executorName", "worker.any", "input", Map.of()));
        assertThat(targeted.subList(0, 4)).extracting(item -> CallApi.object(item.get("workerSelector")).get("input"))
                .containsExactly("w0", "w1", "w2", "w0");
        assertThat(LaneSaturation.ITEMS_PER_GROUP % LaneSaturation.APPEND_BATCH).isZero();
    }

    @Test
    void experimentUsesItsTtlWithoutChangingSelectorOrPayload() {
        assertThat(LaneSaturation.items(LaneSaturation.Path.TASK_ANY, "perf-a", List.of("w"), "p", 0, 1_800_000)
                .getFirst()).containsEntry("ttlMillis", 1_800_000L);
    }

    @Test
    void seedRespectsTaskMutationAdmissionWhileIndependentTasksOverlap() throws Exception {
        var active = new ConcurrentHashMap<String, Object>();
        var seen = ConcurrentHashMap.<String>newKeySet();
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        var conflicts = new AtomicInteger();
        var missingOverlap = new AtomicInteger();
        var firstRequests = new CountDownLatch(2);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(handlers);
            server.createContext("/", exchange -> {
                String task = exchange.getRequestURI().getPath().split("/")[4];
                var batch = Jsons.parseArray(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var admission = new Object();
                if (active.putIfAbsent(task, admission) != null) {
                    conflicts.incrementAndGet();
                    reply(exchange, 400, Map.of("code", 12009));
                    return;
                }
                try {
                    counts.computeIfAbsent(task, ignored -> new AtomicInteger()).incrementAndGet();
                    firstRequests.countDown();
                    try {
                        if (!firstRequests.await(3, TimeUnit.SECONDS)) missingOverlap.incrementAndGet();
                    } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    var result = new LinkedHashMap<String, Object>();
                    for (Object item : batch) {
                        String id = CallApi.string(CallApi.object(item), "messageId");
                        seen.add(id); result.put(id, Map.of("status", "applied"));
                    }
                    // Admission is released before the completed response can start the next append.
                    active.remove(task, admission);
                    reply(exchange, 200, result);
                } finally { active.remove(task, admission); }
            });
            server.start();
            try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
                LaneSaturation.seed(api, LaneSaturation.Path.TASK_ANY,
                        Map.of("perf-a", List.of("a"), "perf-b", List.of("b")),
                        Map.of("perf-a", "task-a", "perf-b", "task-b"), "seed",
                        new ExperimentConfig.Settings(1, 0, 1, 500, 60_000, 1, true));
                assertThat(conflicts).hasValue(0);
                assertThat(missingOverlap).hasValue(0);
                assertThat(counts.get("task-a")).hasValue(5);
                assertThat(counts.get("task-b")).hasValue(5);
                assertThat(seen).hasSize(1000);
            } finally { server.stop(0); }
        }
    }

    @Test
    void firstActionFailureStopsSeedingWithoutRetryAndPreservesSafeDiagnostics() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            reply(exchange, 400, Map.of("code", 12009, "message", "private response content"));
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            assertThatThrownBy(() -> LaneSaturation.seed(api, LaneSaturation.Path.TASK_ANY,
                    Map.of("perf-a", List.of("a"), "perf-b", List.of("b")),
                    Map.of("perf-a", "task-a", "perf-b", "task-b"), "seed",
                    new ExperimentConfig.Settings(1, 0, 1, 1000, 60_000, 1, true)))
                    .isInstanceOfSatisfying(CallApi.ActionFailure.class, error -> {
                        assertThat(error.httpStatus).isEqualTo(400);
                        assertThat(error.errorCode).isEqualTo(12009);
                        assertThat(error).hasMessageContaining("HTTP 400 code=12009")
                                .hasMessageNotContaining("private response content");
                    });
            assertThat(requests.get()).isBetween(1, 2);
        } finally { server.stop(0); }
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Jsons.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
