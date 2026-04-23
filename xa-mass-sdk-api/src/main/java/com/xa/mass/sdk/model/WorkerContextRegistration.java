package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SDK-native worker context registration contract.
 *
 * <p>Registration declares an allocatable resource context. Runtime allocation
 * state starts as IDLE and is owned by the scheduler.
 */
public final class WorkerContextRegistration {

    private final String workerContextId;
    private final String workerId;
    private final String project;
    private final Set<String> routingTags;
    private final Map<String, String> attributes;

    private WorkerContextRegistration(Builder builder) {
        this.workerContextId = builder.workerContextId;
        this.workerId = builder.workerId;
        this.project = builder.project;
        this.routingTags = immutableSetCopy(builder.routingTags);
        this.attributes = immutableMapCopy(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getWorkerContextId() {
        return workerContextId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getProject() {
        return project;
    }

    public Set<String> getRoutingTags() {
        return routingTags;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    private static Set<String> immutableSetCopy(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.copyOf(source);
    }

    private static Map<String, String> immutableMapCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static final class Builder {
        private String workerContextId;
        private String workerId;
        private String project;
        private Set<String> routingTags = Collections.emptySet();
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder workerContextId(String workerContextId) {
            this.workerContextId = workerContextId;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder routingTags(Set<String> routingTags) {
            this.routingTags = routingTags != null ? routingTags : Collections.emptySet();
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public WorkerContextRegistration build() {
            return new WorkerContextRegistration(this);
        }
    }
}
