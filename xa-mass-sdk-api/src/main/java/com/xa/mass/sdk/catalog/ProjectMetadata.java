package com.xa.mass.sdk.catalog;

import java.util.*;

/**
 * Public project metadata exposed through the SDK catalog APIs.
 */
public final class ProjectMetadata {

    private final String code;
    private final String name;
    private final String description;
    private final boolean enabled;
    private final List<String> eventCodes;

    private ProjectMetadata(Builder builder) {
        this.code = requireNonBlank(builder.code, "code");
        this.name = requireNonBlank(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.enabled = builder.enabled;
        this.eventCodes = immutableEventCodes(builder.eventCodes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getEventCodes() {
        return eventCodes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProjectMetadata that)) return false;
        return enabled == that.enabled
                && Objects.equals(code, that.code)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(eventCodes, that.eventCodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, name, description, enabled, eventCodes);
    }

    @Override
    public String toString() {
        return "ProjectMetadata{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", enabled=" + enabled +
                ", eventCodes=" + eventCodes +
                '}';
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<String> immutableEventCodes(Iterable<String> eventCodes) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (eventCodes != null) {
            for (String eventCode : eventCodes) {
                if (eventCode != null && !eventCode.isBlank()) {
                    ordered.add(eventCode.trim());
                }
            }
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    public static final class Builder {
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private List<String> eventCodes = Collections.emptyList();

        private Builder() {
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder eventCodes(List<String> eventCodes) {
            this.eventCodes = eventCodes != null ? eventCodes : Collections.emptyList();
            return this;
        }

        public ProjectMetadata build() {
            return new ProjectMetadata(this);
        }
    }
}
