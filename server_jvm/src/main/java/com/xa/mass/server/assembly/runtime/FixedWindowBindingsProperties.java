package com.xa.mass.server.assembly.runtime;

import java.util.*;

/** Host startup input. No resource or observation lifecycle is held here. */
public record FixedWindowBindingsProperties(Map<String, Pool> pools, Map<String, Group> groups) {
    public FixedWindowBindingsProperties {
        pools = copy(pools, "pools"); groups = copy(groups, "groups");
    }
    public record Pool(String functionName, String timestampProperty, String countProperty,
                       long windowMillis, long maxCount, Feedback feedback) {
        public Pool {
            name(functionName, "function-name"); name(timestampProperty, "timestamp-property"); name(countProperty, "count-property");
            if (timestampProperty.equals(countProperty)) throw invalid("pool property fields must differ");
            positive(windowMillis, "window-millis"); positive(maxCount, "max-count");
            if (feedback == null) throw invalid("pool.feedback is required");
        }
    }
    public record Feedback(List<String> messageEvents) {
        public Feedback {
            messageEvents = names(messageEvents, "feedback.message-events");
            if (messageEvents.isEmpty()) throw invalid("feedback.message-events must not be empty");
        }
    }
    public record Limits(Long windowMillis, Long maxCount) {
        public Limits {
            if (windowMillis != null) positive(windowMillis, "window-millis");
            if (maxCount != null) positive(maxCount, "max-count");
        }
    }
    public record Group(Map<String, Limits> pools) {
        public Group {
            var values = new TreeMap<String, Limits>();
            if (pools != null) pools.forEach((key, value) -> {
                name(key, "group.pools"); values.put(key, value == null ? new Limits(null, null) : value);
            });
            pools = Collections.unmodifiableMap(values);
        }
    }
    private static <T> Map<String, T> copy(Map<String, T> input, String field) {
        var values = new TreeMap<String, T>();
        if (input != null) input.forEach((key, value) -> {
            name(key, field); if (value == null) throw invalid(field + "." + key + " must be an object");
            values.put(key, value);
        });
        return Collections.unmodifiableMap(values);
    }
    private static List<String> names(List<String> input, String field) {
        if (input == null) return List.of();
        input.forEach(value -> name(value, field));
        if (new HashSet<>(input).size() != input.size()) throw invalid(field + " contains duplicate names");
        return input.stream().sorted().toList();
    }
    private static void name(String value, String field) {
        if (value == null || value.isBlank()) throw invalid(field + " must be nonblank");
    }
    private static void positive(long value, String field) {
        if (value <= 0) throw invalid(field + " must be positive");
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("operation=workerBindings.configure xa.mass.worker-assembly.fixed-window: " + message);
    }
}
