package com.xa.mass.server.api.v1.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TaskRpcCallRequest(
        @NotNull @Valid TaskItemRequest item,
        @Positive @Max(60_000) Long waitTimeoutMillis
) {
}
