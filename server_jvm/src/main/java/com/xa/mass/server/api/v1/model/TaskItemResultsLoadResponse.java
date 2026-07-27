package com.xa.mass.server.api.v1.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskItemResultsLoadResponse(
        Map<String, String> results
) {
    public TaskItemResultsLoadResponse {
        Objects.requireNonNull(results, "results");
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }
}
