package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerEventHandlers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerSessionSpec {
    private final String workerId;
    private final String workerGroupId;
    private final Map<String, String> attributes;
    private final WorkerEventHandlers eventHandlers;
    private final WorkerSessionListener listener;

    private WorkerSessionSpec(Builder builder) {
        this.workerId = requireText(builder.workerId, "workerId");
        this.workerGroupId = requireText(builder.workerGroupId, "workerGroupId");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        this.eventHandlers = builder.eventHandlers.build();
        this.listener = builder.listener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String workerId() {
        return workerId;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public WorkerEventHandlers eventHandlers() {
        return eventHandlers;
    }

    public WorkerSessionListener listener() {
        return listener;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static final class Builder {
        private String workerId;
        private String workerGroupId;
        private Map<String, String> attributes = new LinkedHashMap<>();
        private WorkerEventHandlers.Builder eventHandlers = WorkerEventHandlers.builder();
        private WorkerSessionListener listener = WorkerSessionListener.NOOP;

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder event(String eventCode, WorkerDispatchHandler handler) {
            Objects.requireNonNull(handler, "handler is required");
            return eventHandler(eventCode, handler::handle);
        }

        public Builder eventHandler(String eventCode, WorkerEventHandler handler) {
            this.eventHandlers.event(requireText(eventCode, "eventCode"),
                    Objects.requireNonNull(handler, "handler is required"));
            return this;
        }

        public Builder eventHandlers(WorkerEventHandlers eventHandlers) {
            this.eventHandlers.events(eventHandlers);
            return this;
        }

        public Builder listener(WorkerSessionListener listener) {
            this.listener = listener == null ? WorkerSessionListener.NOOP : listener;
            return this;
        }

        public WorkerSessionSpec build() {
            return new WorkerSessionSpec(this);
        }
    }
}
