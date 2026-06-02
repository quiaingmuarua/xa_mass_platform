package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Event-scoped worker capability binding.
 */
public final class WorkerEventBinding {

    private final String eventCode;
    private final List<String> projectCodes;

    private WorkerEventBinding(Builder builder) {
        this.eventCode = requireNonBlank(builder.eventCode, "eventCode");
        this.projectCodes = immutableListCopy(builder.projectCodes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventCode() {
        return eventCode;
    }

    public List<String> getProjectCodes() {
        return projectCodes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerEventBinding that)) return false;
        return Objects.equals(eventCode, that.eventCode)
                && Objects.equals(projectCodes, that.projectCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventCode, projectCodes);
    }

    @Override
    public String toString() {
        return "WorkerEventBinding{" +
                "eventCode='" + eventCode + '\'' +
                ", projectCodes=" + projectCodes +
                '}';
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> immutableListCopy(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized.isEmpty() ? Collections.emptyList() : List.copyOf(normalized);
    }

    public static final class Builder {
        private String eventCode;
        private List<String> projectCodes = Collections.emptyList();

        private Builder() {
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : Collections.emptyList();
            return this;
        }

        public WorkerEventBinding build() {
            return new WorkerEventBinding(this);
        }
    }
}
