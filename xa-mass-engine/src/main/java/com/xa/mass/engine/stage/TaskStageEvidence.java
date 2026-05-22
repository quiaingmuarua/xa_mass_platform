package com.xa.mass.engine.stage;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Task-work stage evidence.
 *
 * <p>Stage evidence is not a stable-final task result row. It is an
 * owner-local proof input for progress/stage workflows that must stay separate
 * from public result convergence.</p>
 */
public final class TaskStageEvidence {

    private final String taskId;
    private final String messageId;
    private final String stageName;
    private final long stageVersion;
    private final String stageStatus;
    private final String detail;
    private final Instant observedAt;
    private final Map<String, Object> attributes;

    private TaskStageEvidence(Builder builder) {
        this.taskId = requireNonBlank(builder.taskId, "taskId");
        this.messageId = requireNonBlank(builder.messageId, "messageId");
        this.stageName = requireNonBlank(builder.stageName, "stageName");
        if (builder.stageVersion < 0) {
            throw new IllegalArgumentException("stageVersion must be >= 0");
        }
        this.stageVersion = builder.stageVersion;
        this.stageStatus = requireNonBlank(builder.stageStatus, "stageStatus");
        this.detail = normalizeNullable(builder.detail);
        this.observedAt = builder.observedAt == null ? Instant.EPOCH : builder.observedAt;
        this.attributes = immutableAttributes(builder.attributes);
    }

    public static Builder builder(String taskId, String messageId, String stageName, long stageVersion) {
        return new Builder(taskId, messageId, stageName, stageVersion);
    }

    public String taskId() {
        return taskId;
    }

    public String messageId() {
        return messageId;
    }

    public String stageName() {
        return stageName;
    }

    public long stageVersion() {
        return stageVersion;
    }

    public String stageStatus() {
        return stageStatus;
    }

    public String detail() {
        return detail;
    }

    public Instant observedAt() {
        return observedAt;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    private static Map<String, Object> immutableAttributes(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = normalizeNullable(entry.getKey());
            if (key != null) {
                values.put(key, entry.getValue());
            }
        }
        return values.isEmpty() ? Map.of() : Collections.unmodifiableMap(values);
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
        if (!(o instanceof TaskStageEvidence that)) {
            return false;
        }
        return stageVersion == that.stageVersion
                && Objects.equals(taskId, that.taskId)
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(stageName, that.stageName)
                && Objects.equals(stageStatus, that.stageStatus)
                && Objects.equals(detail, that.detail)
                && Objects.equals(observedAt, that.observedAt)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, messageId, stageName, stageVersion, stageStatus, detail, observedAt, attributes);
    }

    public static final class Builder {
        private final String taskId;
        private final String messageId;
        private final String stageName;
        private final long stageVersion;
        private String stageStatus;
        private String detail;
        private Instant observedAt;
        private Map<String, Object> attributes = Map.of();

        private Builder(String taskId, String messageId, String stageName, long stageVersion) {
            this.taskId = taskId;
            this.messageId = messageId;
            this.stageName = stageName;
            this.stageVersion = stageVersion;
        }

        public Builder stageStatus(String stageStatus) {
            this.stageStatus = stageStatus;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder observedAt(Instant observedAt) {
            this.observedAt = observedAt;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes == null ? Map.of() : attributes;
            return this;
        }

        public TaskStageEvidence build() {
            return new TaskStageEvidence(this);
        }
    }
}
