package com.xa.mass.sdk.event;

import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Single SDK event registration unit.
 *
 * <p>{@link #getCode()} is the globally unique platform capability identifier.
 * It is the mainline key for SDK-visible metadata, permission checks, worker
 * capability declarations, and runtime dispatch.
 *
 * <p>{@link #getProjectCodes()} is scope metadata only. Project membership may
 * constrain where an event can be invoked, but it is not part of the event's
 * identity and must not be treated as a composite routing key.
 */
public final class EventDefinition {

    private final String code;
    private final String name;
    private final String description;
    private final List<PayloadType> payloadTypes;
    private final List<TaskMode> taskModes;
    private final boolean enabled;
    private final String defaultRoutingCode;
    private final List<String> projectCodes;
    private final PriorityClass priorityClass;
    private final ResponseMode responseMode;
    private final TargetScope targetScope;
    private final EventHandler handler;

    private EventDefinition(Builder builder) {
        this.code = requireNonBlank(builder.code, "code");
        this.name = requireNonBlank(builder.name, "name");
        this.description = builder.description != null ? builder.description : "";
        this.payloadTypes = immutableEnumList(builder.payloadTypes, PayloadType.class);
        this.taskModes = immutableEnumList(builder.taskModes, TaskMode.class);
        this.enabled = builder.enabled;
        this.defaultRoutingCode = blankToNull(builder.defaultRoutingCode);
        this.projectCodes = immutableProjectCodes(builder.projectCodes);
        this.priorityClass = builder.priorityClass != null ? builder.priorityClass : PriorityClass.STANDARD;
        this.responseMode = builder.responseMode != null ? builder.responseMode : ResponseMode.FINAL_RESULT;
        this.targetScope = builder.targetScope != null ? builder.targetScope : TargetScope.WORKER;
        this.handler = builder.handler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCode() {
        return code;
    }

    public String getEventCode() {
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

    public List<String> getProjectCodes() {
        return projectCodes;
    }

    public PriorityClass getPriorityClass() {
        return priorityClass;
    }

    public ResponseMode getResponseMode() {
        return responseMode;
    }

    public TargetScope getTargetScope() {
        return targetScope;
    }

    public EventHandler getHandler() {
        return handler;
    }

    public boolean hasHandler() {
        return handler != null;
    }

    private static List<String> immutableProjectCodes(Iterable<String> projectCodes) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (projectCodes != null) {
            for (String projectCode : projectCodes) {
                if (projectCode != null && !projectCode.isBlank()) {
                    normalized.add(projectCode.trim());
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
        private List<String> projectCodes = Collections.emptyList();
        private PriorityClass priorityClass = PriorityClass.STANDARD;
        private ResponseMode responseMode = ResponseMode.FINAL_RESULT;
        private TargetScope targetScope = TargetScope.WORKER;
        private EventHandler handler;

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

        public Builder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : Collections.emptyList();
            return this;
        }

        public Builder priorityClass(PriorityClass priorityClass) {
            this.priorityClass = priorityClass != null ? priorityClass : PriorityClass.STANDARD;
            return this;
        }

        public Builder responseMode(ResponseMode responseMode) {
            this.responseMode = responseMode != null ? responseMode : ResponseMode.FINAL_RESULT;
            return this;
        }

        public Builder targetScope(TargetScope targetScope) {
            this.targetScope = targetScope != null ? targetScope : TargetScope.WORKER;
            return this;
        }

        public Builder handler(EventHandler handler) {
            this.handler = handler;
            return this;
        }

        public EventDefinition build() {
            return new EventDefinition(this);
        }
    }
}
