package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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

class WorkerLabControlClientTest {

    @Test
    void usesTheFixedLabRoutesAndDecodesSnapshots() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/lab/v1/workers", exchange -> {
            requests.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath());
            String path = exchange.getRequestURI().getRawPath();
            if ("/lab/v1/workers".equals(path)) {
                respond(exchange, 200, Map.of(
                        "workers", List.of(snapshot(false))
                ));
                return;
            }
            if (path.endsWith(":scheduled-stop")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1L);
                exchange.close();
                return;
            }
            if (path.endsWith(":command-checkpoint")) {
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1L);
                    exchange.close();
                    return;
                }
                int status = "PUT".equals(exchange.getRequestMethod())
                        ? 201
                        : 200;
                respond(exchange, status, checkpoint());
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = Jsons.parseObject(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                ));
                assertThat(body).containsEntry("schemaVersion", 2L);
                respond(exchange, 200, snapshot(true));
                return;
            }
            if (path.endsWith(":start")
                    || path.endsWith(":stop")
                    || path.endsWith(":schedule-stop")) {
                respond(exchange, 202, snapshot(false));
                return;
            }
            respond(exchange, 200, snapshot(true));
        });
        server.start();
        try {
            WorkerLabControlClient client = new WorkerLabControlClient(
                    new JsonHttpClient(baseUri(server), Duration.ofSeconds(2))
            );

            assertThat(client.workers()).hasSize(1);
            assertThat(client.worker("group-one", "worker-encoded")
                    .requireWorkerProperties()).containsEntry("labSlot", 1L);
            client.start("group-one", "worker-encoded");
            client.stop("group-one", "worker-encoded");
            client.scheduleStop("group-one", "worker-encoded", 10);
            client.cancelScheduledStop("group-one", "worker-encoded");
            client.replaceProperties(
                    "group-one",
                    "worker-encoded",
                    Map.of("labSlot", 2)
            );
            assertThat(client.armCommandCheckpoint(
                    "group-one",
                    "worker-encoded",
                    "token-one",
                    1_000
            ).state()).isEqualTo("ENTERED");
            assertThat(client.commandCheckpoint(
                    "group-one",
                    "worker-encoded"
            ).enteredAtEpochMillis()).isEqualTo(123L);
            client.releaseCommandCheckpoint("group-one", "worker-encoded");

            assertThat(requests).contains(
                    "GET /lab/v1/workers/group-one/worker-encoded",
                    "POST /lab/v1/workers/group-one/worker-encoded:start",
                    "DELETE /lab/v1/workers/group-one/"
                            + "worker-encoded:scheduled-stop",
                    "PUT /lab/v1/workers/group-one/"
                            + "worker-encoded:command-checkpoint"
            );
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> checkpoint() {
        return Map.of(
                "workerGroupId", "group-one",
                "labWorkerKey", "worker-encoded",
                "checkpointToken", "token-one",
                "maximumHoldMillis", 1_000,
                "state", "ENTERED",
                "enteredAtEpochMillis", 123
        );
    }

    private static Map<String, Object> snapshot(boolean properties) {
        java.util.LinkedHashMap<String, Object> value =
                new java.util.LinkedHashMap<>();
        value.put("workerGroupId", "group-one");
        value.put("labWorkerKey", "worker-encoded");
        value.put("desiredState", "STOPPED");
        value.put("runtimeState", "STOPPED");
        value.put("workerId", null);
        value.put("diagnosticMessage", null);
        value.put("scheduledStopAtEpochMillis", null);
        if (properties) {
            value.put("workerProperties", Map.of("labSlot", 1));
        }
        return value;
    }

    private static URI baseUri(HttpServer server) {
        return URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            Map<String, ?> value
    ) throws IOException {
        byte[] body = Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
