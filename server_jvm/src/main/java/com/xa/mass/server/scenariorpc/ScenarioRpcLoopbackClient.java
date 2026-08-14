package com.xa.mass.server.scenariorpc;

import com.xa.mass.scenariorpc.ScenarioRpcCall;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

final class ScenarioRpcLoopbackClient implements ScenarioRpcCall {

    private final HttpClient http;
    private final URI baseUrl;
    private final long waitTimeoutMillis;
    private final Duration requestTimeout;

    ScenarioRpcLoopbackClient(ScenarioRpcProperties properties) {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        baseUrl = properties.runtimeApiBaseUrl();
        waitTimeoutMillis = properties.waitTimeoutMillis();
        requestTimeout = Duration.ofMillis(
                properties.requestTimeoutMillis()
        );
    }

    @Override
    public Map<String, Object> call(
            String workerGroupId,
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("messageId", identifier(messageId));
        item.put("eventCode", identifier(eventCode));
        item.put("createdAtMillis", System.currentTimeMillis());
        item.put("payload", payload);
        item.put("allocationRule", Map.of());

        URI uri = baseUrl.resolve(
                "/api/v1/worker-groups/"
                        + identifier(workerGroupId)
                        + "/items:call"
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Jsons.toJson(Map.of(
                                "item", item,
                                "waitTimeoutMillis", waitTimeoutMillis
                        )),
                        StandardCharsets.UTF_8
                ))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(
                            StandardCharsets.UTF_8
                    )
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Scenario RPC loopback call was interrupted",
                    error
            );
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Scenario RPC loopback call failed",
                    error
            );
        }
        if (response.statusCode() == 202) {
            throw new IllegalStateException(
                    "Scenario RPC item remained pending"
            );
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Scenario RPC loopback returned status "
                            + response.statusCode()
            );
        }
        Map<String, Object> body = Jsons.parseObject(response.body());
        if (!"succeeded".equals(body.get("status"))) {
            throw new IllegalStateException(
                    "Scenario RPC loopback result was not succeeded"
            );
        }
        Object opaque = body.get("opaqueResultPayload");
        if (!(opaque instanceof String encoded) || encoded.isBlank()) {
            throw new IllegalStateException(
                    "Scenario RPC result payload is missing"
            );
        }
        return Jsons.parseObject(encoded);
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid RPC identifier");
        }
        return value;
    }
}
