package com.xa.mass.sdk.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

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

    private EventMetadata(Builder builder) {
        this.code = Objects.requireNonNull(builder.code, "code");
        this.name = Objects.requireNonNull(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.payloadTypes = immutableEnumList(builder.payloadTypes, PayloadType.class);
        this.taskModes = immutableEnumList(builder.taskModes, TaskMode.class);
        this.enabled = builder.enabled;
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

        public EventMetadata build() {
            return new EventMetadata(this);
        }
    }
}
