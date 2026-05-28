package com.xa.mass.client.worker;

import java.time.Instant;
import java.util.Map;

public record WorkerStateReport(
        String workerId,
        Long stateVersion,
        String state,
        String reason,
        Instant observedAt,
        Map<String, String> attributes
) {
    public WorkerStateReport {
        attributes = WorkerRequestSupport.copyStringMap(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workerId;
        private Long stateVersion;
        private String state;
        private String reason;
        private Instant observedAt;
        private Map<String, String> attributes = WorkerRequestSupport.mutableMap();

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder stateVersion(Long stateVersion) {
            this.stateVersion = stateVersion;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder available() {
            this.state = "AVAILABLE";
            return this;
        }

        public Builder degraded() {
            this.state = "DEGRADED";
            return this;
        }

        public Builder draining() {
            this.state = "DRAINING";
            return this;
        }

        public Builder offline() {
            this.state = "OFFLINE";
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder observedAt(Instant observedAt) {
            this.observedAt = observedAt;
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

        public WorkerStateReport build() {
            return new WorkerStateReport(workerId, stateVersion, state, reason, observedAt, attributes);
        }
    }
}
