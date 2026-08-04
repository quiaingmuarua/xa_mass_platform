package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record WorkerGroupUpsertRequest(
        Map<String, Object> attributes,
        @NotNull List<@NotBlank String> eventCodes
) {
    public WorkerGroupUpsertRequest {
        attributes = attributes == null ? Map.of() : attributes;
        if (eventCodes != null) {
            eventCodes = List.copyOf(eventCodes);
        }
    }
}
