package com.xa.mass.integration.workercapability.runtimeapi;

import com.xa.mass.integration.workercapability.process.RpcProcess;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerGroupRpcClient implements RpcProcess.RpcCall {

    private final RuntimeApiHttpClient http;

    public WorkerGroupRpcClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    @Override
    public Map<String, Object> call(
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
        item.put(
                "payload",
                Collections.unmodifiableMap(new LinkedHashMap<>(payload))
        );
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
