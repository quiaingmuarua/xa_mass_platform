package com.xa.mass.task.runtime;

import java.util.Map;

public record BacklogFrameV1(
        String messageId,
        String eventCode,
        Map<String, Object> payloadJson,
        String payloadRef
) {

    public BacklogFrameV1 {
        messageId = TaskRuntimeContractChecks.requireText(messageId, "messageId");
        eventCode = TaskRuntimeContractChecks.optionalText(eventCode);
        payloadJson = TaskRuntimeContractChecks.copyPayload(payloadJson);
        payloadRef = TaskRuntimeContractChecks.optionalText(payloadRef);
    }

    public static BacklogFrameV1 from(AppendItemInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        return new BacklogFrameV1(input.messageId(), input.eventCode(), input.payloadJson(), input.payloadRef());
    }

    public AppendItemInput toAppendItemInput() {
        return new AppendItemInput(messageId, eventCode, payloadJson, payloadRef);
    }
}
