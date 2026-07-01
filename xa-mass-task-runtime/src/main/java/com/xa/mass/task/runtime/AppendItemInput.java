package com.xa.mass.task.runtime;

import java.util.Map;

public record AppendItemInput(
        String messageId,
        String eventCode,
        Map<String, Object> payloadJson,
        String payloadRef
) {

    public AppendItemInput(String messageId, Map<String, Object> payloadJson) {
        this(messageId, null, payloadJson, null);
    }

    public AppendItemInput {
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        eventCode = TaskRuntimeContractChecks.optionalText(eventCode);
        payloadJson = TaskRuntimeContractChecks.copyPayload(payloadJson);
        payloadRef = TaskRuntimeContractChecks.optionalText(payloadRef);
    }
}
