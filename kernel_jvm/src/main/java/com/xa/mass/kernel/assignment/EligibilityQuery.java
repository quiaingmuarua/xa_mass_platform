package com.xa.mass.kernel.assignment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable bounded query structure. The bound Rule alone interprets its fields and values. */
public record EligibilityQuery(Map<String, List<String>> query) {
    public EligibilityQuery {
        if (query == null || query.size() > 100) {
            throw new IllegalArgumentException("query requires an object with at most 100 fields");
        }
        var copy = new TreeMap<String, List<String>>();
        for (Map.Entry<?, ?> entry : query.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()
                    || !(entry.getValue() instanceof List<?> values)
                    || values.isEmpty() || values.size() > 100
                    || values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
                throw new IllegalArgumentException("query fields require 1..100 non-blank strings");
            }
            copy.put(key, values.stream().map(String.class::cast).toList());
        }
        query = Collections.unmodifiableMap(copy);
    }

    /** Capture raw JSON/storage before a typed binder can coerce scalar values to strings. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    @SuppressWarnings("unchecked")
    public static EligibilityQuery parse(Map<?, ?> query) {
        return new EligibilityQuery((Map<String, List<String>>) query);
    }

    @Override
    @JsonValue
    public Map<String, List<String>> query() {
        return query;
    }
}
