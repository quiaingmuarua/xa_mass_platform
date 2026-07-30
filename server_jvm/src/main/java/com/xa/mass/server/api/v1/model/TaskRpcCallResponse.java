package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRpcCallResponse(
        TaskRpcCallStatus status,
        String taskId,
        String messageId,
        @Nullable String opaqueResultPayload
) {

    public static TaskRpcCallResponse succeeded(
            String taskId,
            String messageId,
            String opaqueResultPayload
    ) {
        return new TaskRpcCallResponse(
                TaskRpcCallStatus.SUCCEEDED,
                taskId,
                messageId,
                opaqueResultPayload
        );
    }

    public static TaskRpcCallResponse pending(
            String taskId,
            String messageId
    ) {
        return new TaskRpcCallResponse(
                TaskRpcCallStatus.PENDING,
                taskId,
                messageId,
                null
        );
    }
}
