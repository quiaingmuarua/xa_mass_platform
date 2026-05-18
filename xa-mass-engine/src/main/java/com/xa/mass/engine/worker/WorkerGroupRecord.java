package com.xa.mass.engine.worker;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Engine-internal WorkerGroup capability declaration.
 */
public final class WorkerGroupRecord {

    private final String groupId;
    private final String adapterNodeId;
    private final Set<EventBinding> eventBindings;
    private final Set<String> projectCodes;
    private final Map<String, String> defaultAttributes;
    private final int defaultMaxConcurrentWork;

    private WorkerGroupRecord(Builder builder) {
        this.groupId = requireNonBlank(builder.groupId, "groupId");
        this.adapterNodeId = normalizeNullable(builder.adapterNodeId);
        this.eventBindings = immutableBindingSet(builder.eventBindings);
        this.projectCodes = immutableProjectCodes(builder.projectCodes, this.eventBindings);
        this.defaultAttributes = immutableStringMap(builder.defaultAttributes);
        this.defaultMaxConcurrentWork = Math.max(1, builder.defaultMaxConcurrentWork);
    }

    public static Builder builder(String groupId) {
        return new Builder(groupId);
    }

    public String groupId() {
        return groupId;
    }

    public String adapterNodeId() {
        return adapterNodeId;
    }

    public Set<EventBinding> eventBindings() {
        return eventBindings;
    }

    public Set<String> projectCodes() {
        return projectCodes;
    }

    public Set<String> eventCodes() {
        if (eventBindings.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> eventCodes = new LinkedHashSet<>();
        for (EventBinding binding : eventBindings) {
            eventCodes.add(binding.eventCode());
        }
        return Collections.unmodifiableSet(eventCodes);
    }

    public Map<String, String> defaultAttributes() {
        return defaultAttributes;
    }

    public int defaultMaxConcurrentWork() {
        return defaultMaxConcurrentWork;
    }

    Set<EventKey> eventKeys() {
        LinkedHashSet<EventKey> keys = new LinkedHashSet<>();
        for (EventBinding binding : eventBindings) {
            keys.addAll(binding.eventKeys());
        }
        return keys.isEmpty() ? Set.of() : Collections.unmodifiableSet(keys);
    }

    boolean supportsProject(String projectCode) {
        String normalized = normalizeNullable(projectCode);
        return normalized != null && projectCodes.contains(normalized);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Set<EventBinding> immutableBindingSet(Collection<EventBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<EventBinding> normalized = new LinkedHashSet<>();
        for (EventBinding binding : bindings) {
            if (binding != null) {
                normalized.add(binding);
            }
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    private static Set<String> immutableProjectCodes(Collection<String> projectCodes,
                                                     Collection<EventBinding> eventBindings) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (projectCodes != null) {
            for (String projectCode : projectCodes) {
                String value = normalizeNullable(projectCode);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        if (eventBindings != null) {
            for (EventBinding binding : eventBindings) {
                if (binding != null) {
                    normalized.addAll(binding.projectCodes());
                }
            }
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
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
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorkerGroupRecord that)) {
            return false;
        }
        return defaultMaxConcurrentWork == that.defaultMaxConcurrentWork
                && Objects.equals(groupId, that.groupId)
                && Objects.equals(adapterNodeId, that.adapterNodeId)
                && Objects.equals(eventBindings, that.eventBindings)
                && Objects.equals(projectCodes, that.projectCodes)
                && Objects.equals(defaultAttributes, that.defaultAttributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, adapterNodeId, eventBindings, projectCodes,
                defaultAttributes, defaultMaxConcurrentWork);
    }

    @Override
    public String toString() {
        return "WorkerGroupRecord{" +
                "groupId='" + groupId + '\'' +
                ", adapterNodeId='" + adapterNodeId + '\'' +
                ", eventBindings=" + eventBindings +
                ", projectCodes=" + projectCodes +
                ", defaultAttributes=" + defaultAttributes +
                ", defaultMaxConcurrentWork=" + defaultMaxConcurrentWork +
                '}';
    }

    public static final class Builder {
        private final String groupId;
        private String adapterNodeId;
        private Collection<EventBinding> eventBindings = Set.of();
        private Collection<String> projectCodes = Set.of();
        private Map<String, String> defaultAttributes = Map.of();
        private int defaultMaxConcurrentWork = 1;

        private Builder(String groupId) {
            this.groupId = groupId;
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder eventBindings(Collection<EventBinding> eventBindings) {
            this.eventBindings = eventBindings == null ? Set.of() : eventBindings;
            return this;
        }

        public Builder projectCodes(Collection<String> projectCodes) {
            this.projectCodes = projectCodes == null ? Set.of() : projectCodes;
            return this;
        }

        public Builder defaultAttributes(Map<String, String> defaultAttributes) {
            this.defaultAttributes = defaultAttributes == null ? Map.of() : defaultAttributes;
            return this;
        }

        public Builder defaultMaxConcurrentWork(int defaultMaxConcurrentWork) {
            this.defaultMaxConcurrentWork = defaultMaxConcurrentWork;
            return this;
        }

        public WorkerGroupRecord build() {
            return new WorkerGroupRecord(this);
        }
    }
}
