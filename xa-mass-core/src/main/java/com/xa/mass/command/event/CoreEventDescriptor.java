package com.xa.mass.command.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Core runtime descriptor for control-plane events.
 */
public final class CoreEventDescriptor {

    private final String event;
    private final String summary;
    private final List<String> projectCodes;
    private final boolean enabled;

    private CoreEventDescriptor(Builder builder) {
        this.event = requireNonBlank(builder.event, "event");
        this.summary = builder.summary == null ? "" : builder.summary;
        this.projectCodes = immutableList(builder.projectCodes);
        this.enabled = builder.enabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEvent() {
        return event;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getProjectCodes() {
        return projectCodes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static List<String> immutableList(Iterable<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
        }
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(normalized));
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private String event;
        private String summary = "";
        private List<String> projectCodes = Collections.emptyList();
        private boolean enabled = true;

        private Builder() {
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : Collections.emptyList();
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public CoreEventDescriptor build() {
            return new CoreEventDescriptor(this);
        }
    }
}
