package com.xa.mass.sdk.model;

import java.util.*;

/**
 * SDK-native worker registration contract.
 *
 * <p>Registration declares worker execution identity and WorkerGroup
 * membership. WorkerGroupDeclaration owns capability truth. Runtime online
 * state is produced by worker transport connect/heartbeat events.
 */
public final class WorkerRegistration {

    private final String workerId;
    private final String workerGroupId;
    private final String transportHint;
    private final int maxConcurrentWork;
    private final Map<String, String> attributes;

    private WorkerRegistration(Builder builder) {
        this.workerId = builder.workerId;
        this.workerGroupId = builder.workerGroupId;
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

    public String getWorkerGroupId() {
        return workerGroupId;
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
                && Objects.equals(workerGroupId, that.workerGroupId)
                && Objects.equals(transportHint, that.transportHint)
                && maxConcurrentWork == that.maxConcurrentWork
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                workerId,
                workerGroupId,
                transportHint,
                maxConcurrentWork,
                attributes
        );
    }

    @Override
    public String toString() {
        return "WorkerRegistration{" +
                "workerId='" + workerId + '\'' +
                ", workerGroupId='" + workerGroupId + '\'' +
                ", transportHint='" + transportHint + '\'' +
                ", maxConcurrentWork=" + maxConcurrentWork +
                ", attributes=" + attributes +
                '}';
    }

    private static Map<String, String> immutableMapCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static final class Builder {
        private String workerId;
        private String workerGroupId;
        private String transportHint;
        private int maxConcurrentWork = 1;
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
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
