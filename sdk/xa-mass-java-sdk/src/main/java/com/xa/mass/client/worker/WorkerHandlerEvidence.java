package com.xa.mass.client.worker;

import java.util.List;
import java.util.Map;

public record WorkerHandlerEvidence(
        String workerId,
        Long evidenceVersion,
        List<String> eventCodes,
        Map<String, String> attributes,
        String agentVersion
) {
    public WorkerHandlerEvidence {
        eventCodes = WorkerRequestSupport.copyList(eventCodes);
        attributes = WorkerRequestSupport.copyStringMap(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workerId;
        private Long evidenceVersion;
        private List<String> eventCodes = WorkerRequestSupport.mutableList();
        private Map<String, String> attributes = WorkerRequestSupport.mutableMap();
        private String agentVersion;

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder evidenceVersion(Long evidenceVersion) {
            this.evidenceVersion = evidenceVersion;
            return this;
        }

        public Builder eventCodes(List<String> eventCodes) {
            this.eventCodes = eventCodes == null ? WorkerRequestSupport.mutableList() : new java.util.ArrayList<>(eventCodes);
            return this;
        }

        public Builder eventCode(String eventCode) {
            this.eventCodes.add(eventCode);
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

        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
            return this;
        }

        public WorkerHandlerEvidence build() {
            return new WorkerHandlerEvidence(workerId, evidenceVersion, eventCodes, attributes, agentVersion);
        }
    }
}
