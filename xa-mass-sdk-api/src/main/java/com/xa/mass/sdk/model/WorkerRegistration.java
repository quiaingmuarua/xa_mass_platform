package com.xa.mass.sdk.model;

import java.util.*;

/**
 * SDK-native worker registration contract.
 *
 * <p>Registration declares worker execution identity and its adapter-node /
 * group relation. WorkerGroupDeclaration owns capability truth. Runtime online
 * state is produced by worker transport connect/heartbeat events.
 */
public final class WorkerRegistration {

    private final String workerId;
    private final String adapterNodeId;
    private final String workerGroupId;
    @Deprecated(forRemoval = false)
    private final List<String> supportedProjects;
    @Deprecated(forRemoval = false)
    private final List<String> supportedEventCodes;
    private final List<WorkerEventBinding> eventBindings;
    private final String adapterId;
    private final String transportHint;
    private final int maxConcurrentWork;
    private final Map<String, String> attributes;

    private WorkerRegistration(Builder builder) {
        this.workerId = builder.workerId;
        this.adapterNodeId = builder.adapterNodeId;
        this.workerGroupId = builder.workerGroupId;
        this.supportedProjects = immutableListCopy(builder.supportedProjects);
        this.supportedEventCodes = immutableListCopy(builder.supportedEventCodes);
        this.eventBindings = immutableBindingCopy(builder.eventBindings);
        this.adapterId = builder.adapterId;
        this.transportHint = builder.transportHint;
        this.maxConcurrentWork = Math.max(1, builder.maxConcurrentWork);
        this.attributes = immutableMapCopy(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getAdapterNodeId() {
        return adapterNodeId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    /**
     * @deprecated Capability truth is {@link WorkerGroupDeclaration}. This
     * coarse grouping view remains only as a compatibility read model.
     */
    @Deprecated(forRemoval = false)
    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    /**
     * @deprecated Capability truth is {@link WorkerGroupDeclaration}. This
     * derived flat event list remains only as a compatibility read model.
     */
    @Deprecated(forRemoval = false)
    public List<String> getSupportedEventCodes() {
        return supportedEventCodes;
    }

    public List<WorkerEventBinding> getEventBindings() {
        return eventBindings;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public int getMaxConcurrentWork() {
        return maxConcurrentWork;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkerRegistration that)) return false;
        return Objects.equals(workerId, that.workerId)
                && Objects.equals(adapterNodeId, that.adapterNodeId)
                && Objects.equals(workerGroupId, that.workerGroupId)
                && Objects.equals(supportedProjects, that.supportedProjects)
                && Objects.equals(supportedEventCodes, that.supportedEventCodes)
                && Objects.equals(eventBindings, that.eventBindings)
                && Objects.equals(adapterId, that.adapterId)
                && Objects.equals(transportHint, that.transportHint)
                && maxConcurrentWork == that.maxConcurrentWork
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                workerId,
                adapterNodeId,
                workerGroupId,
                supportedProjects,
                supportedEventCodes,
                eventBindings,
                adapterId,
                transportHint,
                maxConcurrentWork,
                attributes
        );
    }

    @Override
    public String toString() {
        return "WorkerRegistration{" +
                "workerId='" + workerId + '\'' +
                ", adapterNodeId='" + adapterNodeId + '\'' +
                ", workerGroupId='" + workerGroupId + '\'' +
                ", supportedProjects=" + supportedProjects +
                ", supportedEventCodes=" + supportedEventCodes +
                ", eventBindings=" + eventBindings +
                ", adapterId='" + adapterId + '\'' +
                ", transportHint='" + transportHint + '\'' +
                ", maxConcurrentWork=" + maxConcurrentWork +
                ", attributes=" + attributes +
                '}';
    }

    private static List<String> immutableListCopy(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(source);
    }

    private static Map<String, String> immutableMapCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<WorkerEventBinding> immutableBindingCopy(List<WorkerEventBinding> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(source);
    }

    public static final class Builder {
        private String workerId;
        private String adapterNodeId;
        private String workerGroupId;
        private List<String> supportedProjects = Collections.emptyList();
        private List<String> supportedEventCodes = Collections.emptyList();
        private List<WorkerEventBinding> eventBindings = Collections.emptyList();
        private String adapterId;
        private String transportHint;
        private int maxConcurrentWork = 1;
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
            return this;
        }

        /**
         * @deprecated Prefer WorkerGroup declaration for capability truth.
         */
        @Deprecated(forRemoval = false)
        public Builder supportedProjects(List<String> supportedProjects) {
            this.supportedProjects = supportedProjects != null ? supportedProjects : Collections.emptyList();
            return this;
        }

        /**
         * @deprecated Prefer WorkerGroup declaration for capability truth.
         */
        @Deprecated(forRemoval = false)
        public Builder supportedEventCodes(List<String> supportedEventCodes) {
            this.supportedEventCodes = supportedEventCodes != null
                    ? supportedEventCodes
                    : Collections.emptyList();
            return this;
        }

        public Builder eventBindings(List<WorkerEventBinding> eventBindings) {
            this.eventBindings = eventBindings != null ? eventBindings : Collections.emptyList();
            return this;
        }

        public Builder adapterId(String adapterId) {
            this.adapterId = adapterId;
            return this;
        }

        public Builder transportHint(String transportHint) {
            this.transportHint = transportHint;
            return this;
        }

        public Builder maxConcurrentWork(int maxConcurrentWork) {
            this.maxConcurrentWork = maxConcurrentWork;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public WorkerRegistration build() {
            return new WorkerRegistration(this);
        }
    }
}
