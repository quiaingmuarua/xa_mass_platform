package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.EventKey;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Event-scoped WorkerGroup capability declaration.
 */
public final class EventBinding {

    private final String eventCode;
    private final List<String> projectCodes;

    private EventBinding(String eventCode, Collection<String> projectCodes) {
        this.eventCode = requireNonBlank(eventCode, "eventCode");
        this.projectCodes = normalizeNonEmpty(projectCodes, "projectCodes");
    }

    public static EventBinding of(String eventCode, Collection<String> projectCodes) {
        return new EventBinding(eventCode, projectCodes);
    }

    public String eventCode() {
        return eventCode;
    }

    public List<String> projectCodes() {
        return projectCodes;
    }

    Set<EventKey> eventKeys() {
        Set<EventKey> keys = new LinkedHashSet<>();
        for (String projectCode : projectCodes) {
            keys.add(new EventKey(projectCode, eventCode));
        }
        return keys.isEmpty() ? Set.of() : Collections.unmodifiableSet(keys);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> normalizeNonEmpty(Collection<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return List.copyOf(normalized);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EventBinding that)) {
            return false;
        }
        return Objects.equals(eventCode, that.eventCode)
                && Objects.equals(projectCodes, that.projectCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventCode, projectCodes);
    }

    @Override
    public String toString() {
        return "EventBinding{" +
                "eventCode='" + eventCode + '\'' +
                ", projectCodes=" + projectCodes +
                '}';
    }
}
