package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.integration.workerlab.RuntimeApiClient.TaskItem;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeApiClientTest {

    @Test
    void observesWorkersAndRunsFiniteTaskCalls() throws Exception {
        List<Map<String, Object>> requests = new ArrayList<>();
        AtomicInteger exports = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> body = requestBody(exchange);
            requests.add(body);
            if (path.endsWith("/workers:preview")) {
                respondJson(exchange, 200, Map.of(
                        "unreadableCount", 0,
                        "workers", List.of(Map.of(
                                "workerGroupId", "group-1",
                                "workerId", "worker-1",
                                "workerProperties", Map.of(
                                        "clientWorkerKey", "client-1",
                                        "labSlot", 901
                                )
                        ))
                ));
            } else if (path.endsWith("/workers:network-observe")) {
                respondJson(exchange, 200, Map.of(
                        "statesByWorkerId", Map.of(
                                "worker-1", "connected"
                        )
                ));
            } else if (path.endsWith("/workers:scheduling-observe")) {
                respondJson(exchange, 200, Map.of(
                        "statesByWorkerId", Map.of(
                                "worker-1", "recovery"
                        )
                ));
            } else if ("/api/v1/tasks".equals(path)) {
                respondJson(exchange, 200, Map.of("taskId", "task-1"));
            } else if (path.endsWith("/items")) {
                respondJson(exchange, 200, Map.of(
                        "results", Map.of(
                                "message-1", Map.of("status", "succeeded"),
                                "message-2", Map.of("status", "succeeded")
                        )
                ));
            } else if (path.endsWith("/approve")) {
                respondJson(exchange, 200, Map.of("status", "approved"));
            } else if (path.endsWith("/results:export")
                    && exports.getAndIncrement() == 0) {
                respondJson(exchange, 400, Map.of(
                        "code", 12010,
                        "message", "not ready"
                ));
            } else if (path.endsWith("/results:export")) {
                respondBinary(
                        exchange,
                        200,
                        Jsons.toJson(Map.of("messageId", "message-1"))
                                + "\n"
                                + Jsons.toJson(Map.of(
                                        "messageId",
                                        "message-2"
                                ))
                                + "\n"
                );
            } else {
                respondJson(exchange, 404, Map.of());
            }
        });
        server.start();
        try {
            RuntimeApiClient client = new RuntimeApiClient(
                    new JsonHttpClient(baseUri(server), Duration.ofSeconds(2))
            );

            assertThat(client.previewWorkers("group-1").get("client-1")
                    .workerProperties()).containsEntry("labSlot", 901L);
            assertThat(client.observeNetwork(
                    "adapter-1",
                    List.of("worker-1")
            )).containsEntry("worker-1", "connected");
            assertThat(client.observeScheduling(
                    "group-1",
                    List.of("worker-1")
            )).containsEntry("worker-1", "recovery");
            assertThat(client.createTask(
                    "group-1",
                    Map.of("worker.labSlot", Map.of("$eq", 901))
            )).isEqualTo("task-1");
            client.appendItems("task-1", List.of(
                    new TaskItem("message-1", "event.one", Map.of()),
                    new TaskItem("message-2", "event.one", Map.of())
            ));
            client.approveTask("task-1");
            assertThat(client.exportResults("task-1", 1_000).ready())
                    .isFalse();
            assertThat(client.exportResults("task-1", 1_000).messageIds())
                    .containsExactlyInAnyOrder("message-1", "message-2");

            assertThat(requests).anySatisfy(body -> assertThat(body)
                    .containsEntry("workerGroupId", "group-1")
                    .containsKey("allocationRule"));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> requestBody(HttpExchange exchange)
            throws IOException {
        byte[] encoded = exchange.getRequestBody().readAllBytes();
        return encoded.length == 0
                ? Map.of()
                : Jsons.parseObject(new String(
                        encoded,
                        StandardCharsets.UTF_8
                ));
    }

    private static URI baseUri(HttpServer server) {
        return URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private static void respondJson(
            HttpExchange exchange,
            int status,
            Map<String, ?> value
    ) throws IOException {
        respondBinary(exchange, status, Jsons.toJson(value));
    }

    private static void respondBinary(
            HttpExchange exchange,
            int status,
            String value
    ) throws IOException {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
