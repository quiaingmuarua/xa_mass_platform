package com.xa.mass.server.api.v1.model;

import java.util.Map;

public record WorkerIndexedPropertiesPatchResponse(
        Map<String, CommandResultResponse> results
) {
    public WorkerIndexedPropertiesPatchResponse {
        results = Map.copyOf(results);
    }
}
