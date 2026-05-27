package com.xa.mass.runtime.worker;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Worker-originated state report for bounded projection only.
 */
public final class WorkerStateReport {

    private final String workerId;
    private final long stateVersion;
    private final String state;
    private final String reason;
    private final Instant observedAt;
    private final Map<String, String> attributes;

    private WorkerStateReport(Builder builder) {
        this.workerId = requireNonBlank(builder.workerId, "workerId");
        if (builder.stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        this.stateVersion = builder.stateVersion;
        this.state = requireNonBlank(builder.state, "state");
        this.reason = normalizeNullable(builder.reason);
        this.observedAt = builder.observedAt == null ? Instant.EPOCH : builder.observedAt;
        this.attributes = immutableStringMap(builder.attributes);
    }

    public static Builder builder(String workerId, long stateVersion, String state) {
        return new Builder(workerId, stateVersion, state);
    }

    public String workerId() {
        return workerId;
    }

    public long stateVersion() {
        return stateVersion;
    }

    public String state() {
        return state;
    }

    public String reason() {
        return reason;
    }

    public Instant observedAt() {
        return observedAt;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeNullable(entry.getKey());
            String value = normalizeNullable(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkerStateReport that)) {
            return false;
        }
        return stateVersion == that.stateVersion
                && Objects.equals(workerId, that.workerId)
                && Objects.equals(state, that.state)
                && Objects.equals(reason, that.reason)
                && Objects.equals(observedAt, that.observedAt)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId, stateVersion, state, reason, observedAt, attributes);
    }

    public static final class Builder {
        private final String workerId;
        private final long stateVersion;
        private final String state;
        private String reason;
        private Instant observedAt;
        private Map<String, String> attributes = Map.of();

        private Builder(String workerId, long stateVersion, String state) {
            this.workerId = workerId;
            this.stateVersion = stateVersion;
            this.state = state;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder observedAt(Instant observedAt) {
            this.observedAt = observedAt;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? Map.of() : attributes;
            return this;
        }

        public WorkerStateReport build() {
            return new WorkerStateReport(this);
        }
    }
}
