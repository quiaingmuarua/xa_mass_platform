package com.xa.mass.workermatching;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Operator-free request; the bound Rule interprets its fields. Count is a target or a take quantity. */
public record EligibilityQuery(Map<String, List<String>> query, int count) {
    public EligibilityQuery {
        // Configuration binders represent an empty query object as null.
        query = query == null ? Map.of() : query;
        if (query.size() > 100 || count < 1 || count > 1000) {
            throw new IllegalArgumentException("at most 100 query fields and count in 1..1000 required");
        }
        var normalized = new TreeMap<String, List<String>>();
        query.forEach((key, values) -> {
            if (key == null || key.isBlank() || values == null || values.isEmpty() || values.size() > 100
                    || values.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("query fields require 1..100 non-blank strings");
            }
            normalized.put(key, List.copyOf(values));
        });
        query = Collections.unmodifiableMap(normalized);
    }
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Eligibility query field: " + field);
    }

}
