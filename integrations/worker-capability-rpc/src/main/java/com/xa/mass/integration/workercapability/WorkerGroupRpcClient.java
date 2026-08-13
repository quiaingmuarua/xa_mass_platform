package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkerGroupRpcClient {

    private final RuntimeApiHttpClient http;

    WorkerGroupRpcClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    Map<String, Object> call(
            String workerGroupId,
            String messageId,
            String eventCode,
            Map<String, Object> payload,
            long waitTimeoutMillis
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(
                "messageId",
                RuntimeApiHttpClient.identifier(messageId)
        );
        item.put("eventCode", eventCode);
        item.put("createdAtMillis", System.currentTimeMillis());
        item.put("payload", Map.copyOf(payload));
        item.put("allocationRule", Map.of());

        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/worker-groups/"
                        + RuntimeApiHttpClient.identifier(workerGroupId)
                        + "/items:call",
                Map.of(
                        "item",
                        item,
                        "waitTimeoutMillis",
                        waitTimeoutMillis
                )
        );
        if (response.statusCode() == 202) {
            throw new IllegalStateException(
                    "RPC remained pending for workerGroupId="
                            + workerGroupId
                            + " messageId="
                            + messageId
            );
        }
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "workerGroupItem.call"
        );
        if (!"succeeded".equals(response.body().get("status"))) {
            throw new IllegalStateException(
                    "RPC response was not succeeded for workerGroupId="
                            + workerGroupId
                            + " messageId="
                            + messageId
            );
        }
        Object payloadValue = response.body().get(
                "opaqueResultPayload"
        );
        if (!(payloadValue instanceof String)
                || ((String) payloadValue).isBlank()) {
            throw new IllegalStateException(
                    "RPC result payload is missing for workerGroupId="
                            + workerGroupId
                            + " messageId="
                            + messageId
            );
        }
        return Jsons.parseObject((String) payloadValue);
    }
}
