package com.xa.mass.client.worker;

import com.xa.mass.client.worker.handler.WorkerEventHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Protocol-neutral Java SDK definition for one managed external worker runtime.
 */
public final class WorkerRuntimeDefinition {
    private final String workerId;
    private final String workerGroupId;
    private final Map<String, String> attributes;
    private final Map<String, WorkerEventHandler> eventHandlers;

    private WorkerRuntimeDefinition(Builder builder) {
        this.workerId = requireText(builder.workerId, "workerId");
        this.workerGroupId = requireText(builder.workerGroupId, "workerGroupId");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        this.eventHandlers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.eventHandlers));
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

    public Map<String, WorkerEventHandler> eventHandlers() {
        return eventHandlers;
    }

    public Set<String> eventCodes() {
        return eventHandlers.keySet();
    }

    static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static final class Builder {
        private String workerId;
        private String workerGroupId;
        private Map<String, String> attributes = new LinkedHashMap<>();
        private Map<String, WorkerEventHandler> eventHandlers = new LinkedHashMap<>();

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
            this.attributes.put(requireText(key, "attribute key"), value);
            return this;
        }

        public Builder event(String eventCode, WorkerEventHandler handler) {
            return eventHandler(eventCode, handler);
        }

        public Builder eventHandler(String eventCode, WorkerEventHandler handler) {
            this.eventHandlers.put(requireText(eventCode, "eventCode"),
                    Objects.requireNonNull(handler, "handler is required"));
            return this;
        }

        public Builder eventHandlers(Map<String, WorkerEventHandler> eventHandlers) {
            if (eventHandlers != null) {
                eventHandlers.forEach(this::eventHandler);
            }
            return this;
        }

        public WorkerRuntimeDefinition build() {
            return new WorkerRuntimeDefinition(this);
        }
    }
}
