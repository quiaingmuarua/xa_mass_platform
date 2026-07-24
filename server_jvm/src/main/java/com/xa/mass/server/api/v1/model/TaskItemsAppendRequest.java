package com.xa.mass.server.api.v1.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record TaskItemsAppendRequest(
        @NotNull List<@Valid TaskItemRequest> items
) {
    public TaskItemsAppendRequest {
        if (items != null) {
            items = List.copyOf(items);
        }
    }
}
