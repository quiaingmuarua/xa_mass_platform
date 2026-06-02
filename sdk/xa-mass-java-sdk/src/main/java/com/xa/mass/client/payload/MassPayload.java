package com.xa.mass.client.payload;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MassPayload {
    private final Map<String, Object> values;

    private MassPayload(Map<String, Object> values) {
        this.values = values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static MassPayload of(Map<String, Object> values) {
        return new MassPayload(values);
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Optional<String> getString(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String text) {
            return Optional.of(text);
        }
        return Optional.of(String.valueOf(value));
    }

    public String requiredString(String key) {
        String value = getString(key)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .orElseThrow(() -> new MassPayloadException("Missing required payload field: " + key));
        return value;
    }

    public URI requiredUri(String key) {
        try {
            return URI.create(requiredString(key));
        } catch (IllegalArgumentException e) {
            throw new MassPayloadException("Invalid URI payload field: " + key, e);
        }
    }

    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(values);
    }
}
