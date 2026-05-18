package com.xa.mass.command.event;

import com.xa.mass.base.event.DeliveryAcknowledgementMode;
import com.xa.mass.base.event.EventConvergenceMode;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Core runtime descriptor for control-plane events.
 */
public final class CoreEventDescriptor {

    private final String event;
    private final String name;
    private final String summary;
    private final String description;
    private final List<String> payloadTypes;
    private final List<String> taskModes;
    private final String defaultRoutingCode;
    private final List<String> projectCodes;
    private final boolean enabled;
    private final PriorityClass priorityClass;
    private final ResponseMode responseMode;
    private final DeliveryAcknowledgementMode deliveryAcknowledgementMode;
    private final EventConvergenceMode convergenceMode;
    private final TargetScope targetScope;

    private CoreEventDescriptor(Builder builder) {
        this.event = requireNonBlank(builder.event, "event");
        this.name = blankToNull(builder.name);
        this.summary = builder.summary == null ? "" : builder.summary;
        this.description = builder.description == null ? "" : builder.description;
        this.payloadTypes = immutableList(builder.payloadTypes);
        this.taskModes = immutableList(builder.taskModes);
        this.defaultRoutingCode = blankToNull(builder.defaultRoutingCode);
        this.projectCodes = immutableList(builder.projectCodes);
        this.enabled = builder.enabled;
        this.priorityClass = builder.priorityClass != null ? builder.priorityClass : PriorityClass.STANDARD;
        this.responseMode = builder.responseMode != null ? builder.responseMode : ResponseMode.FINAL_RESULT;
        this.deliveryAcknowledgementMode = builder.deliveryAcknowledgementMode != null
                ? builder.deliveryAcknowledgementMode
                : DeliveryAcknowledgementMode.fromResponseMode(this.responseMode);
        this.convergenceMode = builder.convergenceMode != null
                ? builder.convergenceMode
                : EventConvergenceMode.fromResponseMode(this.responseMode);
        this.targetScope = builder.targetScope != null ? builder.targetScope : TargetScope.WORKER;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEvent() {
        return event;
    }

    public String getName() {
        return name;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getPayloadTypes() {
        return payloadTypes;
    }

    public List<String> getTaskModes() {
        return taskModes;
    }

    public String getDefaultRoutingCode() {
        return defaultRoutingCode;
    }

    public List<String> getProjectCodes() {
        return projectCodes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public PriorityClass getPriorityClass() {
        return priorityClass;
    }

    public ResponseMode getResponseMode() {
        return responseMode;
    }

    public DeliveryAcknowledgementMode getDeliveryAcknowledgementMode() {
        return deliveryAcknowledgementMode;
    }

    public EventConvergenceMode getConvergenceMode() {
        return convergenceMode;
    }

    public TargetScope getTargetScope() {
        return targetScope;
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Builder {
        private String event;
        private String name;
        private String summary = "";
        private String description = "";
        private List<String> payloadTypes = Collections.emptyList();
        private List<String> taskModes = Collections.emptyList();
        private String defaultRoutingCode;
        private List<String> projectCodes = Collections.emptyList();
        private boolean enabled = true;
        private PriorityClass priorityClass = PriorityClass.STANDARD;
        private ResponseMode responseMode = ResponseMode.FINAL_RESULT;
        private DeliveryAcknowledgementMode deliveryAcknowledgementMode;
        private EventConvergenceMode convergenceMode;
        private TargetScope targetScope = TargetScope.WORKER;

        private Builder() {
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            if ((this.description == null || this.description.isBlank())
                    && summary != null && !summary.isBlank()) {
                this.description = summary;
            }
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            if ((this.summary == null || this.summary.isBlank())
                    && description != null && !description.isBlank()) {
                this.summary = description;
            }
            return this;
        }

        public Builder payloadTypes(List<String> payloadTypes) {
            this.payloadTypes = payloadTypes != null ? payloadTypes : Collections.emptyList();
            return this;
        }

        public Builder taskModes(List<String> taskModes) {
            this.taskModes = taskModes != null ? taskModes : Collections.emptyList();
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

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
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

        public Builder deliveryAcknowledgementMode(DeliveryAcknowledgementMode deliveryAcknowledgementMode) {
            this.deliveryAcknowledgementMode = deliveryAcknowledgementMode;
            return this;
        }

        public Builder convergenceMode(EventConvergenceMode convergenceMode) {
            this.convergenceMode = convergenceMode;
            return this;
        }

        public Builder targetScope(TargetScope targetScope) {
            this.targetScope = targetScope != null ? targetScope : TargetScope.WORKER;
            return this;
        }

        public CoreEventDescriptor build() {
            return new CoreEventDescriptor(this);
        }
    }
}
