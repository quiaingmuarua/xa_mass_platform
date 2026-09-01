package com.xa.mass.integration.workercorrectness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.CallStatus;
import com.xa.mass.integration.workercorrectness.RuntimeApiClient.TaskItem;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RuntimeApiClientTest {

    @Test
    void callsManagedTaskBatchAndReturnsOnlyStatuses() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<Map<String, Object>> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/v1/tasks", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(readBody(exchange));
            respond(exchange, Map.of(
                    "results", Map.of(
                            "message-1", Map.of("status", "succeeded"),
                            "message-2", Map.of("status", "failed"),
                            "message-3", Map.of("status", "not_observed")
                    )
            ));
        });
        server.start();
        try {
            RuntimeApiClient client = new RuntimeApiClient(
                    baseUri(server),
                    Duration.ofSeconds(2)
            );

            assertEquals(Map.of(
                    "message-1", CallStatus.SUCCEEDED,
                    "message-2", CallStatus.FAILED,
                    "message-3", CallStatus.NOT_OBSERVED
            ), client.callItems("group-1", List.of(
                    new TaskItem("message-1", "event.one", Map.of()),
                    new TaskItem("message-2", "event.two", Map.of()),
                    new TaskItem("message-3", "event.three", Map.of())
            ), 500L));
            assertEquals(
                    "/api/v1/tasks/scenario-rpc-group-1/items:call"
                    , path.get());
            assertEquals(500L, requestBody.get().get("waitTimeoutMillis"));
            List<?> items = assertInstanceOf(
                    List.class,
                    requestBody.get().get("items")
            );
            assertTrue(items.stream().allMatch(raw -> Map.of().equals(
                    RuntimeApiClient.objectMap(raw, "item")
                            .get("allocationRule")
            )));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> readBody(HttpExchange exchange)
            throws IOException {
        return Jsons.parseObject(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        ));
    }

    private static URI baseUri(HttpServer server) {
        return URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    private static void respond(
            HttpExchange exchange,
            Map<String, ?> value
    ) throws IOException {
        byte[] body = Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
