package com.xa.mass.server.api.v1.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record WorkerUpsertRequest(
        @NotBlank String endpointManagerId,
        Map<String, Object> attributes,
        List<@NotBlank String> dynamicAttributeNames
) {
    public WorkerUpsertRequest {
        attributes = attributes == null ? Map.of() : attributes;
        dynamicAttributeNames = dynamicAttributeNames == null
                ? List.of()
                : List.copyOf(dynamicAttributeNames);
    }
}
