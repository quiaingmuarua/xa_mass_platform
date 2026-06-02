package com.xa.mass.client.worker;

import java.util.List;
import java.util.Map;

public record WorkerCapabilityReport(
        String workerId,
        Long capabilityVersion,
        List<String> availableEventCodes,
        Map<String, String> schedulingAttributes,
        String agentVersion
) {
    public WorkerCapabilityReport {
        availableEventCodes = WorkerRequestSupport.copyList(availableEventCodes);
        schedulingAttributes = WorkerRequestSupport.copyStringMap(schedulingAttributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workerId;
        private Long capabilityVersion;
        private List<String> availableEventCodes = WorkerRequestSupport.mutableList();
        private Map<String, String> schedulingAttributes = WorkerRequestSupport.mutableMap();
        private String agentVersion;

        private Builder() {
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder capabilityVersion(Long capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        public Builder availableEventCodes(List<String> availableEventCodes) {
            this.availableEventCodes = availableEventCodes == null ? WorkerRequestSupport.mutableList() : new java.util.ArrayList<>(availableEventCodes);
            return this;
        }

        public Builder availableEventCode(String eventCode) {
            this.availableEventCodes.add(eventCode);
            return this;
        }

        public Builder schedulingAttributes(Map<String, String> schedulingAttributes) {
            this.schedulingAttributes = schedulingAttributes == null ? WorkerRequestSupport.mutableMap() : new java.util.LinkedHashMap<>(schedulingAttributes);
            return this;
        }

        public Builder schedulingAttribute(String key, String value) {
            this.schedulingAttributes.put(key, value);
            return this;
        }

        public Builder agentVersion(String agentVersion) {
            this.agentVersion = agentVersion;
            return this;
        }

        public WorkerCapabilityReport build() {
            return new WorkerCapabilityReport(workerId, capabilityVersion,
                    availableEventCodes, schedulingAttributes, agentVersion);
        }
    }
}
