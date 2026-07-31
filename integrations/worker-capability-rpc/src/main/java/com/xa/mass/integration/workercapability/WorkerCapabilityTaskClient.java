package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkerCapabilityTaskClient {

    private final RuntimeApiHttpClient http;

    WorkerCapabilityTaskClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    void createItemDrivenTask(
            String taskId,
            String workerGroupId,
            long closeAfterMillis
    ) {
        long emptyCloseAtMillis = Math.addExact(
                System.currentTimeMillis(),
                closeAfterMillis
        );
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks",
                Map.of(
                        "taskId",
                        RuntimeApiHttpClient.identifier(taskId),
                        "workerGroupId",
                        RuntimeApiHttpClient.identifier(workerGroupId),
                        "taskType",
                        "ITEM_DRIVEN",
                        "config",
                        Map.of(
                                "priority",
                                "0",
                                "maximumCandidateWorkers",
                                "1",
                                "maxRetryTimes",
                                "3"
                        ),
                        "emptyCloseAtMillis",
                        emptyCloseAtMillis
                )
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                201,
                "task.create"
        );
    }

    void approveTask(String taskId) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks/"
                        + RuntimeApiHttpClient.identifier(taskId)
                        + "/approve",
                null
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "task.approve"
        );
    }

    void closeTask(String taskId) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks/"
                        + RuntimeApiHttpClient.identifier(taskId)
                        + "/close",
                null
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "task.close"
        );
    }

    Map<String, Object> call(
            String taskId,
            String messageId,
            String eventCode,
            String workerId,
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
        item.put(
                "allocationRule",
                Map.of(
                        "workerId",
                        Map.of(
                                "$eq",
                                RuntimeApiHttpClient.identifier(workerId)
                        )
                )
        );

        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks/"
                        + RuntimeApiHttpClient.identifier(taskId)
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
                    "RPC remained pending for taskId="
                            + taskId
                            + " messageId="
                            + messageId
            );
        }
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "taskItem.call"
        );
        if (!"succeeded".equals(response.body().get("status"))) {
            throw new IllegalStateException(
                    "RPC response was not succeeded for taskId="
                            + taskId
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
                    "RPC result payload is missing for taskId="
                            + taskId
                            + " messageId="
                            + messageId
            );
        }
        return Jsons.parseObject((String) payloadValue);
    }
}
