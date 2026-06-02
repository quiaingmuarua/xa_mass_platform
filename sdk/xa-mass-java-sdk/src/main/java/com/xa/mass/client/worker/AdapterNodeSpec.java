package com.xa.mass.client.worker;

import java.util.Map;

public record AdapterNodeSpec(
        String adapterNodeId,
        String adapterType,
        String adapterVersion,
        String endpointId,
        Boolean enabled,
        Boolean online,
        Map<String, String> attributes
) {
    public AdapterNodeSpec {
        attributes = WorkerRequestSupport.copyStringMap(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String adapterNodeId;
        private String adapterType;
        private String adapterVersion;
        private String endpointId;
        private Boolean enabled;
        private Boolean online;
        private Map<String, String> attributes = WorkerRequestSupport.mutableMap();

        private Builder() {
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder adapterType(String adapterType) {
            this.adapterType = adapterType;
            return this;
        }

        public Builder adapterVersion(String adapterVersion) {
            this.adapterVersion = adapterVersion;
            return this;
        }

        public Builder endpointId(String endpointId) {
            this.endpointId = endpointId;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder online(Boolean online) {
            this.online = online;
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

        public AdapterNodeSpec build() {
            return new AdapterNodeSpec(adapterNodeId, adapterType, adapterVersion, endpointId,
                    enabled, online, attributes);
        }
    }
}
