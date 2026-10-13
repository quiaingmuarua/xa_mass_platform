package com.xa.mass.server.api.v1.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

public record ApiErrorResponse(
        int code,
        String message,
        String requestId,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String taskId
) {
}
