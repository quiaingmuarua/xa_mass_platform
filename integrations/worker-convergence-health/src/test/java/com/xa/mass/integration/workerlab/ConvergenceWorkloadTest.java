package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.integration.workerlab.ConvergenceWorkload.Batch;
import com.xa.mass.integration.workerlab.RuntimeApiClient.CallStatus;
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
import org.junit.jupiter.api.Test;

class ConvergenceWorkloadTest {

    @Test
    void scopesOrdinaryAndCheckpointWaveAllocationRules()
            throws Exception {
        List<Request> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/api/v1/tasks", exchange -> {
            Map<String, Object> body = requestBody(exchange);
            requests.add(new Request(exchange.getRequestURI().getPath(), body));
            Map<String, Object> results = new LinkedHashMap<>();
            for (Object raw : JsonValues.array(body.get("items"), "items")) {
                Map<String, Object> item = JsonValues.object(raw, "item");
                results.put(
                        JsonValues.requiredString(item, "messageId"),
                        Map.of("status", "not_observed")
                );
            }
            respondJson(exchange, results);
        });
        server.start();
        try {
            RuntimeApiClient runtime = new RuntimeApiClient(
                    new JsonHttpClient(baseUri(server), Duration.ofSeconds(2))
            );
            ConvergenceWorkload workload = new ConvergenceWorkload(
                    runtime,
                    "proof"
            );

            List<Batch> wave = workload.submitWave(
                    "wave-1",
                    Map.of(
                            WorkerLabConvergenceSupport.STRING_GROUP,
                            Map.of("worker.convergenceSlot", Map.of("$eq", "B"))
                    ),
                    null
            );

            assertThat(wave).hasSize(2);
            assertThat(workload.offeredItemCount()).isEqualTo(100);
            assertThat(workload.invalidInputCount()).isEqualTo(10);
            assertThat(workload.offeredDelayItemCount()).isEqualTo(1);
            assertThat(workload.offeredFailItemCount()).isEqualTo(1);
            assertThat(workload.witnessMessageIds()).hasSize(2);
            assertThat(wave).allSatisfy(batch -> assertThat(
                    workload.immediateWitnessStatus(batch)
            ).isEqualTo(CallStatus.NOT_OBSERVED));
            assertThat(requests).hasSize(2);
            assertBatch(requests.get(0), false);
            assertBatch(requests.get(1), true);

            Map<String, Object> checkpointRule = Map.of(
                    "workerId", Map.of("$in", List.of("target", "backup")),
                    "worker.labSlot", Map.of("$eq", 1L)
            );
            workload.submitCheckpointWave(
                    "wave-2",
                    Map.of(
                            WorkerLabConvergenceSupport.STRING_GROUP,
                            checkpointRule
                    ),
                    new ConvergenceWorkload.Checkpoint("checkpoint-token")
            );

            assertThat(requests).hasSize(4);
            assertBatch(requests.get(2), false);
            assertCheckpointBatch(requests.get(3), checkpointRule);
        } finally {
            server.stop(0);
        }
    }

    private static void assertCheckpointBatch(
            Request request,
            Map<String, Object> expectedRule
    ) {
        List<Object> items = JsonValues.array(
                request.body().get("items"),
                "items"
        );
        assertThat(items).hasSize(50);
        items.forEach(raw -> assertThat(JsonValues.object(
                JsonValues.object(raw, "item").get("allocationRule"),
                "allocationRule"
        )).containsExactlyEntriesOf(expectedRule));
        Map<String, Object> first = JsonValues.object(items.get(0), "item");
        assertThat(first).containsEntry(
                "eventCode",
                WorkerLabConvergenceSupport.CHECKPOINT_EVENT
        );
        assertThat(JsonValues.object(first.get("payload"), "payload"))
                .containsExactlyEntriesOf(Map.of(
                        "checkpointToken",
                        "checkpoint-token"
                ));
    }

    private static void assertBatch(Request request, boolean stringGroup) {
        assertThat(request.path()).endsWith("/items:call");
        assertThat(request.body()).containsEntry("waitTimeoutMillis", 250L);
        List<Object> items = JsonValues.array(request.body().get("items"), "items");
        assertThat(items).hasSize(50);
        for (int index = 1; index <= items.size(); index++) {
            Map<String, Object> item = JsonValues.object(
                    items.get(index - 1),
                    "item"
            );
            String eventCode = JsonValues.requiredString(
                    item,
                    "eventCode"
            );
            Map<String, Object> payload = JsonValues.object(
                    item.get("payload"),
                    "payload"
            );
            if (stringGroup && index == 2) {
                assertThat(eventCode)
                        .isEqualTo(WorkerLabConvergenceSupport.DELAY_EVENT);
                assertThat(payload).containsExactlyEntriesOf(Map.of(
                        "delayMillis",
                        ConvergenceWorkload.BACKGROUND_DELAY_MILLIS
                ));
            } else if (stringGroup && index == 3) {
                assertThat(eventCode)
                        .isEqualTo(WorkerLabConvergenceSupport.FAIL_EVENT);
                assertThat(payload).isEmpty();
            } else if (index % 10 == 0) {
                if (stringGroup) {
                    assertThat(payload).containsEntry("unexpected", "invalid");
                } else {
                    assertThat(payload).containsEntry(
                            "rawNumber",
                            "not-a-phone-number"
                    );
                }
            }
        }
        Map<String, Object> first = JsonValues.object(items.get(0), "item");
        Map<String, Object> rule = JsonValues.object(
                first.get("allocationRule"),
                "allocationRule"
        );
        if (stringGroup) {
            assertThat(rule).containsKey("worker.convergenceSlot");
        } else {
            assertThat(rule).isEmpty();
        }
        items.stream().skip(1).forEach(raw -> assertThat(JsonValues.object(
                JsonValues.object(raw, "item").get("allocationRule"),
                "allocationRule"
        )).isEmpty());
    }

    private static Map<String, Object> requestBody(HttpExchange exchange)
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

    private static void respondJson(
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

    private record Request(String path, Map<String, Object> body) {
    }
}
