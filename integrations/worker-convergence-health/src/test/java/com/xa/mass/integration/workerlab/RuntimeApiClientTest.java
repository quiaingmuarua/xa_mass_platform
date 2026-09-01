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
import org.junit.jupiter.api.Test;

class RuntimeApiClientTest {

    @Test
    void observesWorkersAndRunsFiniteTaskCalls() throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = requestBody(exchange);
            requests.add(new Request(path, body));
            if (path.endsWith("/workers:preview")) {
                respondJson(exchange, 200, Map.of(
                        "unreadableCount", 0,
                        "workers", List.of(Map.of(
                                "workerGroupId", "group-1",
                                "workerId", "worker-1",
                                "workerProperties", Map.of(
                                        "labInventoryKey", "workers.jsonl",
                                        "labInventoryLine", 1,
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
            } else if (path.endsWith("/tasks:preview")) {
                respondJson(exchange, 200, Map.of(
                        "entries", List.of(
                                Map.of(
                                        "taskId", "task-1",
                                        "scoreBand", "running-normal"
                                ),
                                Map.of(
                                        "taskId", "another-task",
                                        "scoreBand", "terminal"
                                )
                        )
                ));
            } else if (path.endsWith("/items:call")) {
                respondJson(exchange, 200, Map.of(
                        "message-1", Map.of("status", "succeeded"),
                        "message-2", Map.of("status", "not_observed")
                ));
            } else if (path.endsWith("/results:load")) {
                respondJson(exchange, 200, Map.of(
                        "message-1",
                        Map.of(
                                "status", "succeeded",
                                "opaqueResultPayload", "result"
                        ),
                        "message-2",
                        Map.of("status", "not_observed"),
                        "message-3",
                        Map.of("status", "failed")
                ));
            } else {
                respondJson(exchange, 404, Map.of());
            }
        });
        server.start();
        try {
            RuntimeApiClient client = new RuntimeApiClient(
                    new JsonHttpClient(baseUri(server), Duration.ofSeconds(2))
            );

            assertThat(client.previewWorkers("group-1")
                    .get("workers.jsonl:1")
                    .workerProperties()).containsEntry("labSlot", 901L);
            assertThat(client.observeNetwork(
                    "adapter-1",
                    List.of("worker-1")
            )).containsEntry("worker-1", "connected");
            assertThat(client.observeScheduling(
                    "group-1",
                    List.of("worker-1")
            )).containsEntry("worker-1", "recovery");
            assertThat(client.previewTaskScoreBands(List.of("task-1")))
                    .containsExactly(Map.entry("task-1", "running-normal"));
            assertThat(client.callItems("task-1", List.of(
                    new TaskItem(
                            "message-1",
                            "event.one",
                            Map.of(),
                            Map.of()
                    ),
                    new TaskItem(
                            "message-2",
                            "event.one",
                            Map.of(),
                            Map.of("worker.slot", Map.of("$eq", "B"))
                    )
            ), 250L)).containsExactly(
                    Map.entry(
                            "message-1",
                            RuntimeApiClient.CallStatus.SUCCEEDED
                    ),
                    Map.entry(
                            "message-2",
                            RuntimeApiClient.CallStatus.NOT_OBSERVED
                    )
            );
            assertThat(client.loadResultStatuses(
                    "task-1",
                    List.of("message-1", "message-2", "message-3")
            )).containsExactly(
                    Map.entry(
                            "message-1",
                            RuntimeApiClient.CallStatus.SUCCEEDED
                    ),
                    Map.entry(
                            "message-2",
                            RuntimeApiClient.CallStatus.NOT_OBSERVED
                    ),
                    Map.entry(
                            "message-3",
                            RuntimeApiClient.CallStatus.FAILED
                    )
            );

            assertThat(requests).anySatisfy(request -> {
                assertThat(request.path()).endsWith("/items:call");
                Map<String, Object> body = Jsons.parseObject(request.body());
                assertThat(body).containsEntry("waitTimeoutMillis", 250L);
                assertThat(body.get("items")).asList().hasSize(2);
            });
            assertThat(requests).anySatisfy(request -> {
                assertThat(request.path()).endsWith("/tasks:preview");
                assertThat(request.body()).isEqualTo("100");
            });
            assertThat(requests).anySatisfy(request -> {
                assertThat(request.path()).endsWith(
                        "/workers:network-observe"
                );
                assertThat(Jsons.parseArray(request.body()))
                        .containsExactly("worker-1");
            });
            assertThat(requests).anySatisfy(request -> {
                assertThat(request.path()).endsWith("/results:load");
                assertThat(Jsons.parseArray(request.body()))
                        .containsExactly(
                                "message-1",
                                "message-2",
                                "message-3"
                        );
            });
        } finally {
            server.stop(0);
        }
    }

    private static String requestBody(HttpExchange exchange)
            throws IOException {
        byte[] encoded = exchange.getRequestBody().readAllBytes();
        return encoded.length == 0
                ? ""
                : new String(encoded, StandardCharsets.UTF_8);
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

    private record Request(String path, String body) {
    }
}
