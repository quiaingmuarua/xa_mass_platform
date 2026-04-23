package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK-native worker registration contract.
 *
 * <p>Registration declares worker identity and capabilities only. Runtime
 * online state is produced by worker transport connect/heartbeat events.
 */
public final class WorkerRegistration {

    private final String workerId;
    private final String workerGroupId;
    private final List<String> supportedProjects;
    private final String transportHint;
    private final Map<String, String> attributes;

    private WorkerRegistration(Builder builder) {
        this.workerId = builder.workerId;
        this.workerGroupId = builder.workerGroupId;
        this.supportedProjects = immutableListCopy(builder.supportedProjects);
        this.transportHint = builder.transportHint;
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

    public List<String> getSupportedProjects() {
        return supportedProjects;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public Map<String, String> getAttributes() {
        return attributes;
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

    public static final class Builder {
        private String workerId;
        private String workerGroupId;
        private List<String> supportedProjects = Collections.emptyList();
        private String transportHint;
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

        public Builder supportedProjects(List<String> supportedProjects) {
            this.supportedProjects = supportedProjects != null ? supportedProjects : Collections.emptyList();
            return this;
        }

        public Builder transportHint(String transportHint) {
            this.transportHint = transportHint;
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
