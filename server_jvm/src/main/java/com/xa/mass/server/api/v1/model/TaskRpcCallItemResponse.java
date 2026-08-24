package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskRpcCallItemResponse(
        TaskRpcCallStatus status,
        @Nullable String opaqueResultPayload
) {

    public static TaskRpcCallItemResponse succeeded(String payload) {
        return new TaskRpcCallItemResponse(
                TaskRpcCallStatus.SUCCEEDED,
                payload
        );
    }

    public static TaskRpcCallItemResponse notObserved() {
        return new TaskRpcCallItemResponse(
                TaskRpcCallStatus.NOT_OBSERVED,
                null
        );
    }
}
