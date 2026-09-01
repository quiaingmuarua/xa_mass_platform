package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AndroidRuntimeApiClientTest {

    private HttpServer server;
    private AndroidRuntimeApiClient client;
    private final AtomicReference<Map<String, Object>> lastItemsCall =
            new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new AndroidRuntimeApiClient(new JsonHttpClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2L)
        ));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void usesPublicRuntimeBoundariesWithoutInterpretingTaskPayload() {
        assertEquals(
                "connected",
                client.networkState("scenario-websocket", "worker-1")
        );
        assertEquals(
                "hot-score-overdue",
                client.schedulingState("android-demo-workers", "worker-1")
        );
        client.callWorker(
                "scenario-websocket",
                "worker-1",
                AndroidWorkerProofConstants.WORKER_PROBE_EVENT,
                "null"
        ).requireSuccessful("probe");
        assertEquals(
                "close-started",
                client.closeCurrentConnection(
                        "scenario-websocket",
                        "worker-1"
                )
        );
        client.requirePropertiesRelation(
                "scenario-websocket",
                "worker-1",
                Map.of("packageName", "test.application")
        );

        AndroidRuntimeApiClient.TaskCall call = client.callItem(
                AndroidWorkerProofConstants.DELAY_EVENT,
                Map.of("delayMillis", 100L),
                1_000L
        );
        assertEquals(AndroidRuntimeApiClient.CallStatus.SUCCEEDED, call.status());
        assertTrue(client.resultObserved(call.messageId()));
    }

    @Test
    void submitsOneBatchWithCallerOwnedAllocationRules() {
        List<AndroidRuntimeApiClient.TaskCall> calls = client.callItems(
                List.of(
                        new AndroidRuntimeApiClient.TaskItemCall(
                                AndroidWorkerProofConstants.DELAY_EVENT,
                                Map.of("delayMillis", 100L),
                                Map.of(
                                        "worker.packageName",
                                        Map.of("$eq", "app.lab1")
                                )
                        ),
                        new AndroidRuntimeApiClient.TaskItemCall(
                                AndroidWorkerProofConstants.DELAY_EVENT,
                                Map.of("delayMillis", 100L),
                                Map.of(
                                        "worker.packageName",
                                        Map.of("$eq", "app.lab2")
                                )
                        )
                ),
                1_000L
        );

        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call ->
                call.status() == AndroidRuntimeApiClient.CallStatus.SUCCEEDED
        ));
        Map<String, Object> request = lastItemsCall.get();
        List<Object> items = JsonValues.array(request.get("items"), "items");
        assertEquals(2, items.size());
        assertEquals(
                Map.of(
                        "worker.packageName",
                        Map.of("$eq", "app.lab1")
                ),
                JsonValues.object(
                        JsonValues.object(items.get(0), "item")
                                .get("allocationRule"),
                        "allocationRule"
                )
        );
        assertEquals(
                Map.of(
                        "worker.packageName",
                        Map.of("$eq", "app.lab2")
                ),
                JsonValues.object(
                        JsonValues.object(items.get(1), "item")
                                .get("allocationRule"),
                        "allocationRule"
                )
        );
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, Object> request = Jsons.parseObject(new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        ));
        Map<String, Object> response;
        if (path.endsWith("workers:network-observe")) {
            response = Map.of(
                    "statesByWorkerId",
                    Map.of("worker-1", "connected")
            );
        } else if (path.endsWith("workers:scheduling-observe")) {
            response = Map.of(
                    "statesByWorkerId",
                    Map.of("worker-1", "hot-score-overdue")
            );
        } else if (path.endsWith("/direct-calls")) {
            response = directCall(request);
        } else if (path.endsWith("/items:call")) {
            lastItemsCall.set(Map.copyOf(request));
            Map<String, Object> results = new LinkedHashMap<>();
            for (Object rawItem : JsonValues.array(request.get("items"), "items")) {
                Map<String, Object> item = JsonValues.object(rawItem, "item");
                results.put(
                        JsonValues.requiredString(item, "messageId"),
                        Map.of("status", "succeeded")
                );
            }
            response = Map.of(
                    "results",
                    results
            );
        } else if (path.endsWith("/results:load")) {
            String messageId = (String) JsonValues.array(
                    request.get("messageIds"),
                    "messageIds"
            ).get(0);
            response = Map.of("results", Map.of(messageId, "opaque-result"));
        } else {
            throw new AssertionError("Unexpected path: " + path);
        }
        write(exchange, response);
    }

    private static Map<String, Object> directCall(Map<String, Object> request) {
        String messageType = JsonValues.requiredString(request, "messageType");
        String target;
        String payload;
        if (request.containsKey("workerPayloads")) {
            target = "worker-1";
            if (AndroidWorkerProofConstants.WORKER_PROPERTIES_EVENT.equals(
                    messageType
            )) {
                payload = Jsons.toJson(Map.of(
                        "properties",
                        Map.of(
                                "runtime", "android",
                                "packageName", "test.application"
                        )
                ));
            } else {
                payload = "null";
            }
        } else {
            target = "scenario-websocket";
            if (AndroidWorkerProofConstants.ADAPTER_CLOSE_CURRENT_EVENT.equals(
                    messageType
            )) {
                payload = Jsons.toJson(Map.of(
                        "outcomeByWorkerId",
                        Map.of("worker-1", "close-started")
                ));
            } else {
                Map<String, Object> observation = new LinkedHashMap<>();
                observation.put("updatedAtMillis", 1L);
                observation.put(
                        "properties",
                        Map.of(
                                "runtime", "android",
                                "packageName", "test.application"
                        )
                );
                payload = Jsons.toJson(Map.of(
                        "propertiesByWorkerId",
                        Map.of("worker-1", observation)
                ));
            }
        }
        return Map.of(
                "status", "observed",
                "results", Map.of(
                        target,
                        Map.of(
                                "status", "observed",
                                "outcomeCode", "200",
                                "opaqueResultPayload", payload
                        )
                )
        );
    }

    private static void write(
            HttpExchange exchange,
            Map<String, Object> response
    ) throws IOException {
        byte[] body = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
