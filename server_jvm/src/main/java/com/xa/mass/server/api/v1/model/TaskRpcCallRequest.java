package com.xa.mass.server.api.v1.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TaskRpcCallRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotNull @Valid TaskItemRequest> items,
        @Positive @Max(60_000) Long waitTimeoutMillis
) {
    public TaskRpcCallRequest {
        if (items != null) {
            items = List.copyOf(items);
        }
    }
}
