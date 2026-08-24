package com.xa.mass.server.api.v1.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskCreateResponse(
        String taskId,
        RuntimeCommandStatus status,
        @Nullable String reason
) {
}
