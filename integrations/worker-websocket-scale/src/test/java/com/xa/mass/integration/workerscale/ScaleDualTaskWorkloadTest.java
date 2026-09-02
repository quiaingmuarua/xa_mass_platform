package com.xa.mass.integration.workerscale;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScaleDualTaskWorkloadTest {

    @TempDir
    Path temporaryDirectory;

    private final AtomicInteger createdTasks = new AtomicInteger();
    private final AtomicInteger taskBLoadRequests = new AtomicInteger();
    private final Map<String, List<String>> messageIdsByTask =
            new LinkedHashMap<>();
    private final List<String> operations = new ArrayList<>();
    private HttpServer server;
    private ScaleApiClient client;

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
        URI base = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
        client = new ScaleApiClient(
                new ScaleHttpClient(base, Duration.ofSeconds(2)),
                new ScaleHttpClient(base, Duration.ofSeconds(2))
        );
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fillsBothTasksBeforeApprovalAndAllowsOneToFinishFirst()
            throws IOException {
        ScaleOptions options = new ScaleOptions(
                ScaleOptions.Phase.INITIAL,
                "proof-a",
                baseUri(),
                baseUri(),
                "group-a",
                "adapter-a",
                1,
                1,
                500,
                Duration.ofSeconds(10),
                Duration.ZERO,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                temporaryDirectory.resolve("baseline.json"),
                temporaryDirectory.resolve("summary.json"),
                temporaryDirectory.resolve("timeline.jsonl")
        );

        ScaleDualTaskWorkload.WorkloadResult result =
                ScaleDualTaskWorkload.run(
                        options,
                        client,
                        List.of("worker-a")
                );

        assertThat(operations.subList(0, 13)).containsExactly(
                "create-task-a",
                "append-task-a-100",
                "append-task-a-100",
                "append-task-a-100",
                "append-task-a-100",
                "append-task-a-100",
                "create-task-b",
                "append-task-b-100",
                "append-task-b-100",
                "append-task-b-100",
                "append-task-b-100",
                "append-task-b-100",
                "approve-task-a"
        );
        assertThat(operations.get(13)).isEqualTo("approve-task-b");
        assertThat(operations).containsOnlyOnce("export-task-a");
        assertThat(operations).containsOnlyOnce("export-task-b");
        assertThat(result.appendBatchCount()).isEqualTo(10);
        assertThat(result.taskA().succeeded()).isEqualTo(500);
        assertThat(result.taskB().succeeded()).isEqualTo(500);
        assertThat(result.minimumConnected()).isEqualTo(1);

        List<Map<String, Object>> timeline = Files.readAllLines(
                options.timelineFile()
        ).stream().map(Jsons::parseObject).toList();
        assertThat(timeline).anySatisfy(entry -> {
            assertThat(entry.get("event"))
                    .isEqualTo("dual-task-workload-progress");
            assertThat(entry.get("taskASucceeded")).isEqualTo(500L);
            assertThat(entry.get("taskBSucceeded")).isEqualTo(0L);
        });
    }

    @Test
    void onlyTheGlobalSuccessfulProgressGapCanTriggerTheStall() {
        long limit = Duration.ofSeconds(60).toNanos();

        assertThat(ScaleDualTaskWorkload.hasStalled(false, limit - 1))
                .isFalse();
        assertThat(ScaleDualTaskWorkload.hasStalled(false, limit)).isTrue();
        assertThat(ScaleDualTaskWorkload.hasStalled(true, limit)).isFalse();
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void networkObservation(HttpExchange exchange) throws IOException {
        List<Object> requested = parseArray(exchange);
        Map<String, Object> states = new LinkedHashMap<>();
        for (Object workerId : requested) {
            states.put((String) workerId, "connected");
        }
        respondJson(exchange, 200, Map.of("statesByWorkerId", states));
    }

    private void taskRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/tasks".equals(path)) {
            String taskId = createdTasks.incrementAndGet() == 1
                    ? "task-a"
                    : "task-b";
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
        entries.add(taskPreviewEntry("task-a", "terminal"));
        entries.add(taskPreviewEntry(
                "task-b",
                taskBLoadRequests.get() > 5 ? "terminal" : "running-visible"
        ));
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
        boolean succeeded = !"task-b".equals(taskId)
                || taskBLoadRequests.incrementAndGet() > 5;
        Map<String, Object> response = new LinkedHashMap<>();
        for (Object raw : requested) {
            response.put(
                    (String) raw,
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
