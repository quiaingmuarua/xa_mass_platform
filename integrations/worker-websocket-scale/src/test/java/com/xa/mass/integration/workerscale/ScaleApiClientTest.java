package com.xa.mass.integration.workerscale;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaleApiClientTest {

    private HttpServer server;
    private ScaleApiClient client;
    private final AtomicReference<Object> appendResponse =
            new AtomicReference<>(Map.of(
                    "message-1", Map.of("status", "applied")
            ));
    private final AtomicReference<Object> approvalResponse =
            new AtomicReference<>(Map.of("status", "applied"));

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lab/v1/workers", exchange -> respond(
                exchange,
                200,
                Map.of("workers", List.of(Map.of(
                        "workerGroupId", "group-a",
                        "labWorkerKey", "workers.jsonl:1",
                        "desiredState", "RUNNING",
                        "runtimeState", "RUNNING",
                        "workerId", "worker-a"
                )))
        ));
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
                "/api/v1/tasks/task-1/items",
                exchange -> respond(exchange, 200, appendResponse.get())
        );
        server.createContext(
                "/api/v1/tasks/task-1/approve",
                exchange -> respond(exchange, 200, approvalResponse.get())
        );
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
    void acceptsCurrentActionOutcomes() {
        client.appendItems("task-1", List.of(new ScaleApiClient.TaskItem(
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
                List.of(new ScaleApiClient.TaskItem(
                        "message-1",
                        "event.one",
                        Map.of()
                ))
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.approveTask("task-1"))
                .isInstanceOf(IllegalStateException.class);
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
