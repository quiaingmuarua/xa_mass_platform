package com.xa.mass.server.api.v1.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TaskItemsAppendResponse(
        Map<String, TaskItemAppendOutcome> results
) {
    public TaskItemsAppendResponse {
        Objects.requireNonNull(results, "results");
        results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }
}
