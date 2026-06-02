package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured JSON-like payload wrapper for SDK v1 task inputs.
 */
public final class JsonInput implements MassInput {

    private final Map<String, Object> data;

    public JsonInput(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            this.data = Collections.emptyMap();
        } else {
            this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    public Map<String, Object> getData() {
        return data;
    }

    @Override
    public Map<String, Object> toTaskMsgInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "json");
        input.put("data", data);
        return Map.copyOf(input);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonInput jsonInput)) return false;
        return Objects.equals(data, jsonInput.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }

    @Override
    public String toString() {
        return "JsonInput{" +
                "data=" + data +
                '}';
    }
}
