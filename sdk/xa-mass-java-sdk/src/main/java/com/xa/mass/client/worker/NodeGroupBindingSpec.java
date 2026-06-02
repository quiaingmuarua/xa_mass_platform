package com.xa.mass.client.worker;

import java.util.Map;

public record NodeGroupBindingSpec(
        String adapterNodeId,
        String workerGroupId,
        String pluginVersion,
        String deploymentVersion,
        Boolean enabled,
        Boolean draining,
        Map<String, String> attributes
) {
    public NodeGroupBindingSpec {
        attributes = WorkerRequestSupport.copyStringMap(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String adapterNodeId;
        private String workerGroupId;
        private String pluginVersion;
        private String deploymentVersion;
        private Boolean enabled;
        private Boolean draining;
        private Map<String, String> attributes = WorkerRequestSupport.mutableMap();

        private Builder() {
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
            return this;
        }

        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        public Builder deploymentVersion(String deploymentVersion) {
            this.deploymentVersion = deploymentVersion;
            return this;
        }

        public Builder enabled(Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder draining(Boolean draining) {
            this.draining = draining;
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

        public NodeGroupBindingSpec build() {
            return new NodeGroupBindingSpec(adapterNodeId, workerGroupId, pluginVersion,
                    deploymentVersion, enabled, draining, attributes);
        }
    }
}
