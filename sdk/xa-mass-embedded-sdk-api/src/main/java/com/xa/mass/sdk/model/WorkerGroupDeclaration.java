package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-native WorkerGroup capability declaration.
 */
public final class WorkerGroupDeclaration {

    private final String groupId;
    private final List<WorkerEventBinding> eventBindings;
    private final Map<String, String> defaultAttributes;
    private final int defaultMaxConcurrentWork;

    private WorkerGroupDeclaration(Builder builder) {
        this.groupId = requireNonBlank(builder.groupId, "groupId");
        this.eventBindings = immutableBindingCopy(builder.eventBindings);
        this.defaultAttributes = immutableMapCopy(builder.defaultAttributes);
        this.defaultMaxConcurrentWork = Math.max(1, builder.defaultMaxConcurrentWork);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getGroupId() {
        return groupId;
    }

    public List<WorkerEventBinding> getEventBindings() {
        return eventBindings;
    }

    public Map<String, String> getDefaultAttributes() {
        return defaultAttributes;
    }

    public int getDefaultMaxConcurrentWork() {
        return defaultMaxConcurrentWork;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerGroupDeclaration that)) return false;
        return defaultMaxConcurrentWork == that.defaultMaxConcurrentWork
                && Objects.equals(groupId, that.groupId)
                && Objects.equals(eventBindings, that.eventBindings)
                && Objects.equals(defaultAttributes, that.defaultAttributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, eventBindings, defaultAttributes, defaultMaxConcurrentWork);
    }

    @Override
    public String toString() {
        return "WorkerGroupDeclaration{" +
                "groupId='" + groupId + '\'' +
                ", eventBindings=" + eventBindings +
                ", defaultAttributes=" + defaultAttributes +
                ", defaultMaxConcurrentWork=" + defaultMaxConcurrentWork +
                '}';
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<WorkerEventBinding> immutableBindingCopy(List<WorkerEventBinding> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(source);
    }

    private static Map<String, String> immutableMapCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                normalized.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        return normalized.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(normalized);
    }

    public static final class Builder {
        private String groupId;
        private List<WorkerEventBinding> eventBindings = Collections.emptyList();
        private Map<String, String> defaultAttributes = Collections.emptyMap();
        private int defaultMaxConcurrentWork = 1;

        private Builder() {
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder eventBindings(List<WorkerEventBinding> eventBindings) {
            this.eventBindings = eventBindings != null ? eventBindings : Collections.emptyList();
            return this;
        }

        public Builder defaultAttributes(Map<String, String> defaultAttributes) {
            this.defaultAttributes = defaultAttributes != null ? defaultAttributes : Collections.emptyMap();
            return this;
        }

        public Builder defaultMaxConcurrentWork(int defaultMaxConcurrentWork) {
            this.defaultMaxConcurrentWork = defaultMaxConcurrentWork;
            return this;
        }

        public WorkerGroupDeclaration build() {
            return new WorkerGroupDeclaration(this);
        }
    }
}
