package com.xa.mass.engine.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerCommandRequest {

    private final String commandId;
    private final String workerId;
    private final String commandType;
    private final String requester;
    private final String reason;
    private final String idempotencyKey;
    private final Long deadlineEpochMillis;
    private final Map<String, Object> payload;

    private WorkerCommandRequest(Builder builder) {
        this.commandId = requireNonBlank(builder.commandId, "commandId");
        this.workerId = requireNonBlank(builder.workerId, "workerId");
        this.commandType = requireNonBlank(builder.commandType, "commandType");
        this.requester = normalizeNullable(builder.requester);
        this.reason = normalizeNullable(builder.reason);
        this.idempotencyKey = normalizeNullable(builder.idempotencyKey);
        this.deadlineEpochMillis = builder.deadlineEpochMillis;
        this.payload = immutablePayload(builder.payload);
    }

    public static Builder builder(String commandId, String workerId, String commandType) {
        return new Builder(commandId, workerId, commandType);
    }

    public String commandId() {
        return commandId;
    }

    public String workerId() {
        return workerId;
    }

    public String commandType() {
        return commandType;
    }

    public String requester() {
        return requester;
    }

    public String reason() {
        return reason;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    boolean sameRequest(WorkerCommandRequest other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(commandId, other.commandId)
                && Objects.equals(workerId, other.workerId)
                && Objects.equals(commandType, other.commandType)
                && Objects.equals(requester, other.requester)
                && Objects.equals(reason, other.reason)
                && Objects.equals(idempotencyKey, other.idempotencyKey)
                && Objects.equals(deadlineEpochMillis, other.deadlineEpochMillis)
                && Objects.equals(payload, other.payload);
    }

    private static Map<String, Object> immutablePayload(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
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

    public static final class Builder {
        private final String commandId;
        private final String workerId;
        private final String commandType;
        private String requester;
        private String reason;
        private String idempotencyKey;
        private Long deadlineEpochMillis;
        private Map<String, Object> payload = Map.of();

        private Builder(String commandId, String workerId, String commandType) {
            this.commandId = commandId;
            this.workerId = workerId;
            this.commandType = commandType;
        }

        public Builder requester(String requester) {
            this.requester = requester;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder deadlineEpochMillis(Long deadlineEpochMillis) {
            this.deadlineEpochMillis = deadlineEpochMillis;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload == null ? Map.of() : payload;
            return this;
        }

        public WorkerCommandRequest build() {
            return new WorkerCommandRequest(this);
        }
    }
}
