package com.xa.mass.integration.workerloadedrecovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadedRecoveryApiClientTest {

    private HttpServer server;
    private LoadedRecoveryApiClient client;
    private final AtomicReference<Object> appendResponse =
            new AtomicReference<>(Map.of(
                    "message-1", Map.of("status", "applied")
            ));
    private final AtomicReference<Object> approvalResponse =
            new AtomicReference<>(Map.of("status", "applied"));
    private final AtomicReference<Object> loadResponse =
            new AtomicReference<>(Map.of(
                    "message-1",
                    Map.of(
                            "status", "succeeded",
                            "opaqueResultPayload", "opaque"
                    )
            ));

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lab/v1/workers", this::labWorkers);
        server.createContext(
                "/api/v1/runtime-view/endpoint-managers/adapter-a/"
                        + "workers:network-observe",
                exchange -> observation(exchange, "connected")
        );
        server.createContext(
                "/api/v1/runtime-view/worker-groups/group-a/"
                        + "workers:scheduling-observe",
                exchange -> observation(exchange, "held-hot")
        );
        server.createContext(
                "/api/v1/runtime-view/tasks:preview",
                exchange -> respond(exchange, 200, Map.of(
                        "sampleLimit", 100,
                        "generatedAt", "2026-09-02T00:00:00Z",
                        "entries", List.of(taskPreviewEntry())
                ))
        );
        server.createContext(
                "/api/v1/tasks/task-1/items",
                exchange -> respond(exchange, 200, appendResponse.get())
        );
        server.createContext(
                "/api/v1/tasks/task-1/approve",
                exchange -> respond(exchange, 200, approvalResponse.get())
        );
        server.createContext(
                "/api/v1/tasks/task-1/results:load",
                exchange -> respond(exchange, 200, loadResponse.get())
        );
        server.start();
        URI base = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
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
    void readsLabIdentityAndTheTwoIndependentRuntimeProjections() {
        assertThat(client.labWorkers()).singleElement().satisfies(worker -> {
            assertThat(worker.workerGroupId()).isEqualTo("group-a");
            assertThat(worker.workerId()).isEqualTo("worker-a");
        });
        assertThat(client.observeNetwork("adapter-a", List.of("worker-a")))
                .containsExactly(Map.entry("worker-a", "connected"));
        assertThat(client.observeScheduling("group-a", List.of("worker-a")))
                .containsExactly(Map.entry("worker-a", "held-hot"));
    }

    @Test
    void submitsOneValidatedLabStopBatch() {
        client.stopWorkers("group-a", List.of(
                "workers.jsonl:1",
                "workers.jsonl:2"
        ));

        assertThatThrownBy(() -> client.stopWorkers(
                "group-a",
                java.util.Collections.nCopies(101, "duplicate")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCurrentActionOutcomes() {
        client.appendItems("task-1", List.of(new LoadedRecoveryApiClient.TaskItem(
                "message-1",
                "event.one",
                Map.of()
        )));
        client.approveTask("task-1");

        approvalResponse.set(Map.of("status", "unchanged"));
        client.approveTask("task-1");
    }

    @Test
    void rejectsLegacyActionOutcomes() {
        appendResponse.set(Map.of(
                "message-1", Map.of("status", "succeeded")
        ));
        approvalResponse.set(Map.of("status", "approved"));

        assertThatThrownBy(() -> client.appendItems(
                "task-1",
                List.of(new LoadedRecoveryApiClient.TaskItem(
                        "message-1",
                        "event.one",
                        Map.of()
                ))
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.approveTask("task-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loadsStrictResultStatusesWithoutExposingPayload() {
        loadResponse.set(Map.of(
                "message-1",
                Map.of(
                        "status", "succeeded",
                        "opaqueResultPayload", "opaque"
                ),
                "message-2", Map.of("status", "failed"),
                "message-3", Map.of("status", "not_observed")
        ));

        assertThat(client.loadResultStatuses(
                "task-1",
                List.of("message-1", "message-2", "message-3")
        )).containsExactly(
                Map.entry(
                        "message-1",
                        LoadedRecoveryApiClient.TaskResultStatus.SUCCEEDED
                ),
                Map.entry(
                        "message-2",
                        LoadedRecoveryApiClient.TaskResultStatus.FAILED
                ),
                Map.entry(
                        "message-3",
                        LoadedRecoveryApiClient.TaskResultStatus.NOT_OBSERVED
                )
        );
    }

    @Test
    void loadsOneThousandResultIdentitiesInOnePublicRequest() {
        List<String> messageIds = new ArrayList<>();
        Map<String, Object> response = new LinkedHashMap<>();
        for (int index = 0; index < 1_000; index++) {
            String messageId = "message-" + index;
            messageIds.add(messageId);
            response.put(messageId, Map.of("status", "not_observed"));
        }
        loadResponse.set(response);

        Map<String, LoadedRecoveryApiClient.TaskResultStatus> statuses =
                client.loadResultStatuses("task-1", messageIds);
        assertThat(statuses).hasSize(1_000);
        assertThat(statuses.values()).containsOnly(
                LoadedRecoveryApiClient.TaskResultStatus.NOT_OBSERVED
        );
    }

    @Test
    void readsTaskTerminalStateFromTheRuntimePreview() {
        assertThat(client.previewTaskScoreBands(List.of("task-1")))
                .containsExactly(Map.entry("task-1", "terminal"));
    }

    @Test
    void rejectsChangedResultIdentitiesAndFields() {
        loadResponse.set(Map.of(
                "other-message", Map.of("status", "not_observed")
        ));
        assertThatThrownBy(() -> client.loadResultStatuses(
                "task-1",
                List.of("message-1")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identities");

        loadResponse.set(Map.of(
                "message-1",
                Map.of(
                        "status", "failed",
                        "opaqueResultPayload", "not-allowed"
                )
        ));
        assertThatThrownBy(() -> client.loadResultStatuses(
                "task-1",
                List.of("message-1")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fields");
    }

    private static void observation(HttpExchange exchange, String state)
            throws IOException {
        List<Object> request = Jsons.parseArray(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        ));
        assertThat(request).containsExactly("worker-a");
        respond(exchange, 200, Map.of(
                "statesByWorkerId", Map.of("worker-a", state)
        ));
    }

    private void labWorkers(HttpExchange exchange) throws IOException {
        if ("/lab/v1/workers:stop".equals(exchange.getRequestURI().getPath())) {
            List<Object> request = Jsons.parseArray(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            assertThat(request).hasSize(2);
            respond(exchange, 202, Map.of("acceptedCount", 2));
            return;
        }
        respond(exchange, 200, Map.of("workers", List.of(Map.of(
                "workerGroupId", "group-a",
                "labWorkerKey", "workers.jsonl:1",
                "desiredState", "RUNNING",
                "runtimeState", "RUNNING",
                "workerId", "worker-a"
        ))));
    }

    private static Map<String, Object> taskPreviewEntry() {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("taskId", "task-1");
        entry.put("scoreBand", "terminal");
        entry.put("task", null);
        entry.put("workerGroup", null);
        return entry;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            Object body
    ) throws IOException {
        byte[] encoded = Jsons.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }
}
