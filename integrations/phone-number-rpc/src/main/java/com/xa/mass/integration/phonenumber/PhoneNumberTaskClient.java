package com.xa.mass.integration.phonenumber;

import com.xa.mass.workerdelivery.json.Jsons;
import java.util.LinkedHashMap;
import java.util.Map;

final class PhoneNumberTaskClient {

    private final RuntimeApiHttpClient http;

    PhoneNumberTaskClient(RuntimeApiHttpClient http) {
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

    String call(
            String taskId,
            String messageId,
            String workerId,
            String rawNumber,
            String defaultRegion,
            long waitTimeoutMillis
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawNumber", rawNumber);
        if (defaultRegion != null && !defaultRegion.isBlank()) {
            payload.put("defaultRegion", defaultRegion);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put(
                "messageId",
                RuntimeApiHttpClient.identifier(messageId)
        );
        item.put(
                "eventCode",
                PhoneNumberIntegrationDefaults.EVENT_CODE
        );
        item.put("createdAtMillis", System.currentTimeMillis());
        item.put("payload", payload);
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
        String encodedResult = (String) payloadValue;
        Jsons.parseObject(encodedResult);
        return encodedResult;
    }
}
