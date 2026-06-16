package com.xa.mass.client.worker;

import java.util.Map;

public record WorkerSpec(
        String workerId,
        String workerGroupId,
        String transportHint,
        Map<String, String> attributes
) {
    public WorkerSpec {
        attributes = WorkerRequestSupport.copyStringMap(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workerId;
        private String workerGroupId;
        private String transportHint;
        private Map<String, String> attributes = WorkerRequestSupport.mutableMap();

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

        public Builder polling() {
            this.transportHint = "polling";
            return this;
        }

        public Builder realtime() {
            this.transportHint = "realtime";
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? WorkerRequestSupport.mutableMap() : new java.util.LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public WorkerSpec build() {
            return new WorkerSpec(workerId, workerGroupId, transportHint, attributes);
        }
    }
}
