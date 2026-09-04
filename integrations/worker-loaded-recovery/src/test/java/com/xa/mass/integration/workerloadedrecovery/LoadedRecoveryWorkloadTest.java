package com.xa.mass.integration.workerloadedrecovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedRecoveryWorkloadTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger createdTasks = new AtomicInteger();
    private final AtomicBoolean completeTasks = new AtomicBoolean();
    private final AtomicInteger disconnectedNetworkScans = new AtomicInteger();
    private final Map<String, List<String>> messageIdsByTask =
            new LinkedHashMap<>();
    private final List<String> operations = new ArrayList<>();
    private HttpServer server;
    private LoadedRecoveryApiClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/runtime-view/endpoint-managers/adapter-a/"
                        + "workers:network-observe",
                this::networkObservation
        );
        server.createContext(
                "/api/v1/runtime-view/tasks:preview",
                this::taskPreview
        );
        server.createContext("/api/v1/tasks", this::taskRequest);
        server.start();
        URI base = baseUri();
        client = new LoadedRecoveryApiClient(
                new LoadedRecoveryHttpClient(base, Duration.ofSeconds(2)),
                new LoadedRecoveryHttpClient(base, Duration.ofSeconds(2))
        );
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fillsTenTasksBeforeApprovalAndAllowsAnyCompletionOrder()
            throws IOException {
        LoadedRecoveryWorkload.InFlightWorkload workload =
                LoadedRecoveryWorkload.start(
                        options(100),
                        client,
                        List.of("worker-a")
                );
        LoadedRecoveryWorkload.MutationCheckpoint checkpoint =
                workload.awaitMutationCheckpoint(client);

        assertThat(checkpoint.taskCount()).isEqualTo(10);
        assertThat(checkpoint.succeededItems()).isEqualTo(100);
        assertThat(checkpoint.unresolvedItems()).isEqualTo(900);
        assertThat(operations).noneMatch(value -> value.startsWith("export-"));

        LoadedRecoveryWorkload.RecoverySnapshot recovery =
                workload.observeAfterMutation(client, true);
        disconnectedNetworkScans.set(1);
        workload.awaitRetainedConnectionsAfterServerRestart(client);
        completeTasks.set(true);
        LoadedRecoveryWorkload.WorkloadResult result =
                workload.awaitCompletion(client, recovery, true);

        assertThat(createdTasks).hasValue(10);
        assertThat(operations.indexOf("approve-task-01")).isEqualTo(20);
        assertThat(operations.subList(0, 20))
                .allMatch(entry -> !entry.startsWith("approve-"));
        for (int ordinal = 1; ordinal <= 10; ordinal++) {
            String taskId = String.format("task-%02d", ordinal);
            assertThat(operations).containsOnlyOnce("export-" + taskId);
        }
        assertThat(result.tasks()).hasSize(10)
                .allSatisfy(task -> {
                    assertThat(task.succeeded()).isEqualTo(100);
                    assertThat(task.exported()).isTrue();
                });
        assertThat(result.appendBatchCount()).isEqualTo(10);
        assertThat(result.minimumConnected()).isEqualTo(1);
        assertThat(result.postRecoveryProgress()).isTrue();
        assertThat(disconnectedNetworkScans).hasValue(0);
        assertThat(operations.stream().filter(value -> value.startsWith("create-")))
                .hasSize(10);
        assertThat(operations.stream().filter(value -> value.startsWith("append-")))
                .hasSize(10);
        assertThat(operations.stream().filter(value -> value.startsWith("approve-")))
                .hasSize(10);

        List<Map<String, Object>> timeline = java.nio.file.Files.readAllLines(
                options(100).timelineFile()
        ).stream().map(Jsons::parseObject).toList();
        assertThat(timeline).anySatisfy(entry -> assertThat(entry.get("event"))
                .isEqualTo("loaded-recovery-workload-progress"));
    }

    @Test
    void distinguishesFirstProgressAndPostProgressStalls() {
        long firstLimit = Duration.ofSeconds(120).toNanos();
        long laterLimit = Duration.ofSeconds(90).toNanos();

        assertThat(LoadedRecoveryWorkload.progressDeadlineExceeded(
                0,
                firstLimit - 1,
                laterLimit
        )).isFalse();
        assertThat(LoadedRecoveryWorkload.progressDeadlineExceeded(
                0,
                firstLimit,
                0
        )).isTrue();
        assertThat(LoadedRecoveryWorkload.progressDeadlineExceeded(
                1,
                firstLimit,
                laterLimit
        )).isTrue();
        assertThat(LoadedRecoveryWorkload.progressDeadlineExceeded(
                0,
                firstLimit,
                laterLimit
        )).isTrue();
        assertThat(LoadedRecoveryWorkload.progressDeadlineExceeded(
                1,
                firstLimit,
                laterLimit - 1
        )).isFalse();
    }

    @Test
    void hardRestartRejectsAFirstSnapshotWithoutBacklog() {
        LoadedRecoveryWorkload.InFlightWorkload workload =
                LoadedRecoveryWorkload.start(
                        options(100),
                        client,
                        List.of("worker-a")
                );
        workload.awaitMutationCheckpoint(client);
        completeTasks.set(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> workload.observeAfterMutation(client, true)
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resumed after the workload completed");
    }

    @Test
    void fixedProductionShapeIsTenTasksWithFiveThousandItemsEach() {
        LoadedRecoveryOptions defaults = LoadedRecoveryOptions.parse(new String[]{
                "--stage=initial-contraction",
                "--topology-file=" + temporaryDirectory.resolve("topology.json"),
                "--baseline-file=" + temporaryDirectory.resolve("baseline.json"),
                "--gate-directory=" + temporaryDirectory.resolve("gate"),
                "--summary-file=" + temporaryDirectory.resolve("summary.json"),
                "--timeline-file=" + temporaryDirectory.resolve("timeline.jsonl")
        });

        assertThat(LoadedRecoveryWorkload.TASK_COUNT).isEqualTo(10);
        assertThat(LoadedRecoveryWorkload.MAXIMUM_CANDIDATE_WORKERS)
                .isEqualTo(100);
        assertThat(defaults.workloadItemsPerTask()).isEqualTo(5_000);
        assertThat(
                LoadedRecoveryWorkload.TASK_COUNT
                        * ((defaults.workloadItemsPerTask() + 99) / 100)
        ).isEqualTo(500);
    }

    @Test
    void fiveThousandStopsFormFiftyOneShotRequests() {
        List<String> keys = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(index -> "workers-" + index)
                .toList();

        assertThat(WorkerLoadedRecoveryMain.stopBatches(keys))
                .hasSize(50)
                .allSatisfy(batch -> assertThat(batch).hasSize(100));
    }

    private LoadedRecoveryOptions options(int itemsPerTask) {
        return new LoadedRecoveryOptions(
                LoadedRecoveryOptions.Stage.INITIAL_CONTRACTION,
                "proof-a",
                baseUri(),
                baseUri(),
                "group-a",
                "adapter-a",
                2,
                1,
                2,
                1,
                itemsPerTask,
                Duration.ofSeconds(10),
                Duration.ZERO,
                Duration.ofMillis(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                temporaryDirectory.resolve("topology.json"),
                temporaryDirectory.resolve("baseline.json"),
                temporaryDirectory.resolve("gate"),
                temporaryDirectory.resolve("summary.json"),
                temporaryDirectory.resolve("timeline.jsonl")
        );
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void networkObservation(HttpExchange exchange) throws IOException {
        List<Object> requested = parseArray(exchange);
        Map<String, Object> states = new LinkedHashMap<>();
        boolean disconnected = disconnectedNetworkScans.getAndUpdate(
                remaining -> Math.max(0, remaining - 1)
        ) > 0;
        for (Object workerId : requested) {
            states.put(
                    (String) workerId,
                    disconnected ? "disconnected" : "connected"
            );
        }
        respondJson(exchange, 200, Map.of("statesByWorkerId", states));
    }

    private void taskRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/tasks".equals(path)) {
            Map<String, Object> request = Jsons.parseObject(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            assertThat(request.get("maximumCandidateWorkers")).isEqualTo(100L);
            String taskId = String.format(
                    "task-%02d",
                    createdTasks.incrementAndGet()
            );
            messageIdsByTask.put(taskId, new ArrayList<>());
            operations.add("create-" + taskId);
            respondJson(exchange, 200, Map.of("taskId", taskId));
            return;
        }

        String[] segments = path.split("/");
        String taskId = segments[4];
        String operation = segments[5];
        switch (operation) {
            case "items" -> appendItems(exchange, taskId);
            case "approve" -> {
                operations.add("approve-" + taskId);
                respondJson(exchange, 200, Map.of("status", "applied"));
            }
            case "results:load" -> loadResults(exchange, taskId);
            case "results:export" -> exportResults(exchange, taskId);
            default -> respondJson(exchange, 404, Map.of("error", "unknown"));
        }
    }

    private void taskPreview(HttpExchange exchange) throws IOException {
        List<Object> entries = new ArrayList<>();
        for (String taskId : messageIdsByTask.keySet()) {
            String scoreBand = completeTasks.get()
                    ? "terminal"
                    : "running-visible";
            entries.add(taskPreviewEntry(taskId, scoreBand));
        }
        respondJson(exchange, 200, Map.of(
                "sampleLimit", 100,
                "generatedAt", "2026-09-02T00:00:00Z",
                "entries", entries
        ));
    }

    private static Map<String, Object> taskPreviewEntry(
            String taskId,
            String scoreBand
    ) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("taskId", taskId);
        entry.put("scoreBand", scoreBand);
        entry.put("task", null);
        entry.put("workerGroup", null);
        return entry;
    }

    private void appendItems(HttpExchange exchange, String taskId)
            throws IOException {
        List<Object> items = parseArray(exchange);
        Map<String, Object> response = new LinkedHashMap<>();
        for (Object raw : items) {
            Map<String, Object> item = object(raw);
            String messageId = (String) item.get("messageId");
            messageIdsByTask.get(taskId).add(messageId);
            response.put(messageId, Map.of("status", "applied"));
        }
        operations.add("append-" + taskId + "-" + items.size());
        respondJson(exchange, 200, response);
    }

    private void loadResults(HttpExchange exchange, String taskId)
            throws IOException {
        List<Object> requested = parseArray(exchange);
        List<String> taskMessageIds = messageIdsByTask.get(taskId);
        int checkpointSuccesses = Math.max(1, taskMessageIds.size() / 10);
        Map<String, Object> response = new LinkedHashMap<>();
        for (Object raw : requested) {
            String messageId = (String) raw;
            boolean succeeded = completeTasks.get()
                    || taskMessageIds.indexOf(messageId) < checkpointSuccesses;
            response.put(
                    messageId,
                    succeeded
                            ? Map.of(
                                    "status", "succeeded",
                                    "opaqueResultPayload", "opaque"
                            )
                            : Map.of("status", "not_observed")
            );
        }
        respondJson(exchange, 200, response);
    }

    private void exportResults(HttpExchange exchange, String taskId)
            throws IOException {
        operations.add("export-" + taskId);
        StringBuilder result = new StringBuilder();
        for (String messageId : messageIdsByTask.get(taskId)) {
            result.append(Jsons.toJson(Map.of(
                    "messageId", messageId,
                    "opaqueResultPayload", "opaque"
            ))).append('\n');
        }
        respond(exchange, 200, result.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Object> parseArray(HttpExchange exchange)
            throws IOException {
        return Jsons.parseArray(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static void respondJson(
            HttpExchange exchange,
            int status,
            Object body
    ) throws IOException {
        respond(
                exchange,
                status,
                Jsons.toJson(body).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body
    ) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
