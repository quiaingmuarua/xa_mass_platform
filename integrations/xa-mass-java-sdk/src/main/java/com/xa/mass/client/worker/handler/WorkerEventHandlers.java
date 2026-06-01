package com.xa.mass.client.worker.handler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class WorkerEventHandlers {
    private static final WorkerEventHandlers EMPTY = new WorkerEventHandlers(Map.of());

    private final Map<String, WorkerEventHandler> handlers;

    private WorkerEventHandlers(Map<String, WorkerEventHandler> handlers) {
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
    }

    public static WorkerEventHandlers empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<WorkerEventHandler> find(String eventCode) {
        return Optional.ofNullable(handlers.get(eventCode));
    }

    public Set<String> eventCodes() {
        return handlers.keySet();
    }

    public Map<String, WorkerEventHandler> asMap() {
        return handlers;
    }

    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    public static final class Builder {
        private final Map<String, WorkerEventHandler> handlers = new LinkedHashMap<>();

        public Builder event(String eventCode, WorkerEventHandler handler) {
            handlers.put(requireText(eventCode, "eventCode"), Objects.requireNonNull(handler, "handler is required"));
            return this;
        }

        public Builder events(WorkerEventHandlers eventHandlers) {
            if (eventHandlers != null) {
                handlers.putAll(eventHandlers.asMap());
            }
            return this;
        }

        public WorkerEventHandlers build() {
            if (handlers.isEmpty()) {
                return EMPTY;
            }
            return new WorkerEventHandlers(handlers);
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " is required");
            }
            return value.trim();
        }
    }
}
