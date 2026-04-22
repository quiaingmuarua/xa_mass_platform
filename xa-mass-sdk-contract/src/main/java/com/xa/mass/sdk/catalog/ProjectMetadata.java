package com.xa.mass.sdk.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

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
        this.code = Objects.requireNonNull(builder.code, "code");
        this.name = Objects.requireNonNull(builder.name, "name");
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

    private static List<String> immutableEventCodes(Iterable<String> eventCodes) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (eventCodes != null) {
            for (String eventCode : eventCodes) {
                if (eventCode != null && !eventCode.isBlank()) {
                    ordered.add(eventCode);
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
