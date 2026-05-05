package com.xa.mass.trace.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable event envelope for all execution trace events.
 *
 * <p>Every event records the state transition using {@code src} (state before)
 * and {@code dst} (state after). For events that are not state transitions
 * (e.g. {@link ExecutionEventType#MSG_DISPATCH_SENT}), both fields are null.</p>
 *
 * <p>The {@code v} field is the schema version and is always {@code 1} for this
 * implementation. It exists to allow safe future schema evolution when querying
 * JSONL files that span multiple versions.</p>
 *
 * <p>Use the {@link Builder} to construct instances.</p>
 */
public final class ExecutionEvent {

    private final int v;
    private final Instant ts;
    private final ExecutionEventType eventType;
    private final String src;
    private final String dst;
    private final String reason;
    private final String taskId;
    private final String messageId;
    private final String workerId;
    private final String adapterId;
    private final Integer retryCount;
    private final Map<String, Object> extra;

    private ExecutionEvent(Builder builder) {
        this.v = 1;
        this.ts = builder.ts != null ? builder.ts : Instant.now();
        this.eventType = builder.eventType;
        this.src = builder.src;
        this.dst = builder.dst;
        this.reason = builder.reason;
        this.taskId = builder.taskId;
        this.messageId = builder.messageId;
        this.workerId = builder.workerId;
        this.adapterId = builder.adapterId;
        this.retryCount = builder.retryCount;
        this.extra = builder.extra == null || builder.extra.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.extra));
    }

    public int getV() {
        return v;
    }

    public Instant getTs() {
        return ts;
    }

    public ExecutionEventType getEventType() {
        return eventType;
    }

    public String getSrc() {
        return src;
    }

    public String getDst() {
        return dst;
    }

    public String getReason() {
        return reason;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public static Builder builder(ExecutionEventType eventType) {
        return new Builder(eventType);
    }

    public static final class Builder {

        private final ExecutionEventType eventType;
        private Instant ts;
        private String src;
        private String dst;
        private String reason;
        private String taskId;
        private String messageId;
        private String workerId;
        private String adapterId;
        private Integer retryCount;
        private Map<String, Object> extra;

        private Builder(ExecutionEventType eventType) {
            if (eventType == null) {
                throw new IllegalArgumentException("eventType must not be null");
            }
            this.eventType = eventType;
        }

        public Builder ts(Instant ts) {
            this.ts = ts;
            return this;
        }

        public Builder src(String src) {
            this.src = src;
            return this;
        }

        public Builder dst(String dst) {
            this.dst = dst;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder adapterId(String adapterId) {
            this.adapterId = adapterId;
            return this;
        }

        public Builder retryCount(Integer retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra = extra;
            return this;
        }

        public ExecutionEvent build() {
            return new ExecutionEvent(this);
        }
    }
}
