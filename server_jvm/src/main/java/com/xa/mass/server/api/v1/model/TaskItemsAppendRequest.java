package com.xa.mass.server.api.v1.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TaskItemsAppendRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotNull @Valid TaskItemRequest> items
) {
    public TaskItemsAppendRequest {
        if (items != null) {
            items = List.copyOf(items);
        }
    }
}
