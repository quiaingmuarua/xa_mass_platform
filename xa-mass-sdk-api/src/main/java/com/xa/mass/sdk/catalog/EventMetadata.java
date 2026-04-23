package com.xa.mass.sdk.catalog;

import java.util.*;

/**
 * Public task-event metadata exposed through the SDK catalog APIs.
 */
public final class EventMetadata {

    private final String code;
    private final String name;
    private final String description;
    private final List<PayloadType> payloadTypes;
    private final List<TaskMode> taskModes;
    private final boolean enabled;
    private final String defaultRoutingCode;

    private EventMetadata(Builder builder) {
        this.code = requireNonBlank(builder.code, "code");
        this.name = requireNonBlank(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.payloadTypes = immutableEnumList(builder.payloadTypes, PayloadType.class);
        this.taskModes = immutableEnumList(builder.taskModes, TaskMode.class);
        this.enabled = builder.enabled;
        this.defaultRoutingCode = blankToNull(builder.defaultRoutingCode);
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

    public List<PayloadType> getPayloadTypes() {
        return payloadTypes;
    }

    public List<TaskMode> getTaskModes() {
        return taskModes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDefaultRoutingCode() {
        return defaultRoutingCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventMetadata that)) return false;
        return enabled == that.enabled
                && Objects.equals(code, that.code)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(payloadTypes, that.payloadTypes)
                && Objects.equals(taskModes, that.taskModes)
                && Objects.equals(defaultRoutingCode, that.defaultRoutingCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, name, description, payloadTypes, taskModes, enabled, defaultRoutingCode);
    }

    @Override
    public String toString() {
        return "EventMetadata{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", payloadTypes=" + payloadTypes +
                ", taskModes=" + taskModes +
                ", enabled=" + enabled +
                ", defaultRoutingCode='" + defaultRoutingCode + '\'' +
                '}';
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static <E extends Enum<E>> List<E> immutableEnumList(Iterable<E> values, Class<E> type) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (values != null) {
            for (E value : values) {
                if (value != null) {
                    set.add(value);
                }
            }
        }
        if (set.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(set));
    }

    public static final class Builder {
        private String code;
        private String name;
        private String description;
        private List<PayloadType> payloadTypes = Collections.emptyList();
        private List<TaskMode> taskModes = Collections.emptyList();
        private boolean enabled = true;
        private String defaultRoutingCode;

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

        public Builder payloadTypes(List<PayloadType> payloadTypes) {
            this.payloadTypes = payloadTypes != null ? payloadTypes : Collections.emptyList();
            return this;
        }

        public Builder taskModes(List<TaskMode> taskModes) {
            this.taskModes = taskModes != null ? taskModes : Collections.emptyList();
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder defaultRoutingCode(String defaultRoutingCode) {
            this.defaultRoutingCode = defaultRoutingCode;
            return this;
        }

        public EventMetadata build() {
            return new EventMetadata(this);
        }
    }
}
