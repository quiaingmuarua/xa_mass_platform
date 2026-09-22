package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.WorkerRef;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerStateConvergenceTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void idleRecoveryOrUnreadableSampleDoesNotBlockRealRestartWitnesses(boolean readable) throws Exception {
        try (var fixture = new Fixture(readable, "none")) {
            WorkerStateConvergence.afterServerRestart(fixture.options, fixture.phaseState);

            assertThat(fixture.sampleReads).hasValue(readable ? 10 : 2);
            assertThat(fixture.propertyWrites).hasValue(1);
            assertThat(fixture.starts).hasValue(1);
            assertThat(fixture.offeredItems).hasValue(100);
            assertThat(fixture.resultReads).hasValue(3); // Property witness and both final-wave witnesses.
            assertThat(fixture.unexpectedRequests).hasValue(0);
            String timeline = Files.readString(directory.resolve(
                    ConvergenceEvidence.timelineFileName(WorkerStateConvergence.LANE)));
            assertThat(timeline).contains(readable ? "\"recovery\":500" : "scheduling-sample-unavailable");
            if (readable) assertThat(timeline).contains("\"recovery\":499");
            Map<String, Object> summary = summary();
            assertThat(summary).containsEntry("status", "succeeded")
                    .containsEntry("workerIdentityStable", true)
                    .containsEntry("serverRestartObserved", true);
            assertThat(((Number) summary.get("workerCount")).intValue()).isEqualTo(1000);
            assertThat(((Number) summary.get("offeredItemCount")).intValue()).isEqualTo(700);
            assertThat(((Number) summary.get("invalidInputCount")).intValue()).isEqualTo(70);
            assertThat(((Number) summary.get("convergedWitnessCount")).intValue()).isEqualTo(14);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "disconnected,server-restarted-connected",
            "identity-changed,Worker identity changed across Runtime Server restart",
            "target-not-hot,slot-c-start-hot",
            "witness-failed,rule-property-witness"
    })
    void requiredRestartEvidenceStillFailsTheProof(String failure, String expected) throws Exception {
        try (var fixture = new Fixture(true, failure)) {
            assertThatThrownBy(() -> WorkerStateConvergence.afterServerRestart(fixture.options, fixture.phaseState))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(expected);
            assertThat(summary()).containsEntry("status", "failed");
            assertThat(fixture.offeredItems).hasValue(0);
            assertThat(fixture.unexpectedRequests).hasValue(0);
            if (failure.equals("disconnected") || failure.equals("identity-changed")) {
                assertThat(fixture.sampleReads).hasValue(0);
                assertThat(fixture.propertyWrites).hasValue(0);
                assertThat(fixture.starts).hasValue(0);
            } else {
                assertThat(fixture.sampleReads).hasValue(10);
                assertThat(fixture.propertyWrites).hasValue(1);
                assertThat(fixture.starts).hasValue(1);
            }
        }
    }

    private Map<String, Object> summary() throws IOException {
        return Jsons.parseObject(Files.readString(directory.resolve(
                ConvergenceEvidence.summaryFileName(WorkerStateConvergence.LANE))));
    }

    private final class Fixture implements AutoCloseable {
        private final WorkerRef target = STRING_WORKERS.get(7);
        private final Map<WorkerRef, String> ids = new LinkedHashMap<>();
        private final HttpServer server;
        private final boolean readable;
        private final String failure;
        private final WorkerLabHarnessOptions options;
        private final Path phaseState = directory.resolve("phase-state.json");
        private final AtomicInteger sampleReads = new AtomicInteger();
        private final AtomicInteger propertyWrites = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger offeredItems = new AtomicInteger();
        private final AtomicInteger resultReads = new AtomicInteger();
        private final AtomicInteger unexpectedRequests = new AtomicInteger();
        private volatile boolean targetStarted;
        private volatile String slot = "A";

        Fixture(boolean readable, String failure) throws Exception {
            this.readable = readable;
            this.failure = failure;
            CONVERGENCE_WORKERS.forEach(worker -> ids.put(worker, "worker-" + ids.size()));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            options = new WorkerLabHarnessOptions(base, base, "adapter", "proof-restart", directory, 1_000, 1_000);
            var coordinates = new LinkedHashMap<String, String>();
            ids.forEach((worker, id) -> coordinates.put(worker.coordinate(), id));
            new StateConvergencePhaseState(options.proofId(), Instant.now(), coordinates,
                    ConvergenceTestData.batches(6), "property-task", "property-message").save(phaseState);
            server.start();
        }

        private String currentId(WorkerRef worker) {
            return failure.equals("identity-changed") && worker.equals(PHONE_WORKERS.getFirst())
                    ? "changed-worker" : ids.get(worker);
        }

        private Map<String, Object> properties(WorkerRef worker) {
            String[] coordinate = worker.labWorkerKey().split(":");
            return Map.of("labInventoryKey", coordinate[0], "labInventoryLine", coordinate[1],
                    "convergenceSlot", worker.equals(target) ? slot : "A");
        }

        private Map<String, Object> snapshot(WorkerRef worker) {
            String state = worker.equals(target) && !targetStarted ? "STOPPED" : "RUNNING";
            return Map.of("workerGroupId", worker.groupId(), "labWorkerKey", worker.labWorkerKey(),
                    "desiredState", state, "runtimeState", state, "workerId", currentId(worker),
                    "workerProperties", properties(worker));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object body;
            int status = 200;
            if (path.equals("/lab/v1/workers")) {
                body = Map.of("workers", CONVERGENCE_WORKERS.stream().map(this::snapshot).toList());
            } else if (path.startsWith("/lab/v1/workers/")) {
                if (exchange.getRequestMethod().equals("PUT")) {
                    propertyWrites.incrementAndGet();
                    slot = "C";
                } else if (path.endsWith(":start")) {
                    starts.incrementAndGet();
                    targetStarted = true;
                    status = 202;
                }
                body = snapshot(target);
            } else if (path.endsWith("/workers:preview")) {
                String group = path.contains(PHONE_GROUP) ? PHONE_GROUP : STRING_GROUP;
                body = Map.of("unreadableCount", 0, "workers", CONVERGENCE_WORKERS.stream()
                        .filter(worker -> group.equals(worker.groupId())).limit(100)
                        .map(worker -> Map.of("workerGroupId", group, "workerId", currentId(worker),
                                "workerProperties", properties(worker))).toList());
            } else if (path.endsWith(":network-observe") || path.endsWith(":scheduling-observe")) {
                List<Object> requested = Jsons.parseArray(request);
                boolean network = path.endsWith(":network-observe");
                if (!network && requested.size() > 1) {
                    sampleReads.incrementAndGet();
                    if (!readable) status = 503;
                }
                var states = new LinkedHashMap<String, String>();
                for (Object raw : requested) {
                    String id = (String) raw;
                    boolean directed = ids.get(target).equals(id);
                    String state = network
                            ? (directed && !targetStarted || failure.equals("disconnected") ? "disconnected" : "connected")
                            : (directed && targetStarted && !failure.equals("target-not-hot") ? "hot-score-overdue" : "recovery");
                    states.put(id, state);
                }
                body = Map.of("statesByWorkerId", states);
            } else if (path.equals("/api/v1/projects/scenario-workers")) {
                body = Map.of("managedTaskIds", Map.of(PHONE_GROUP, "task-" + PHONE_GROUP,
                        STRING_GROUP, "task-" + STRING_GROUP));
            } else if (path.endsWith("/items:call")) {
                var results = new LinkedHashMap<String, Object>();
                for (Object raw : (List<?>) Jsons.parseObject(request).get("items")) {
                    results.put((String) ((Map<?, ?>) raw).get("messageId"), Map.of("status", "not_observed"));
                }
                offeredItems.addAndGet(results.size());
                body = results;
            } else if (path.endsWith("/results:load")) {
                resultReads.incrementAndGet();
                var results = new LinkedHashMap<String, Object>();
                for (Object id : Jsons.parseArray(request)) {
                    results.put((String) id, Map.of("status", failure.equals("witness-failed") ? "failed" : "succeeded"));
                }
                body = results;
            } else if (path.endsWith("/tasks:preview")) {
                body = Map.of("entries", List.of(Map.of("taskId", "property-task", "scoreBand", "RUNNING_VISIBLE")));
            } else {
                unexpectedRequests.incrementAndGet();
                status = 404;
                body = Map.of();
            }
            byte[] encoded = Jsons.toJson(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, encoded.length);
            try (var output = exchange.getResponseBody()) {
                output.write(encoded);
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
