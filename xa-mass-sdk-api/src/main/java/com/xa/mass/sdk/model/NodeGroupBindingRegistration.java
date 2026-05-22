package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-native declaration that an adapter node hosts a worker group.
 */
public final class NodeGroupBindingRegistration {

    private final String adapterNodeId;
    private final String workerGroupId;
    private final String pluginVersion;
    private final String deploymentVersion;
    private final boolean enabled;
    private final boolean draining;
    private final Map<String, String> attributes;

    private NodeGroupBindingRegistration(Builder builder) {
        this.adapterNodeId = requireNonBlank(builder.adapterNodeId, "adapterNodeId");
        this.workerGroupId = requireNonBlank(builder.workerGroupId, "workerGroupId");
        this.pluginVersion = blankToNull(builder.pluginVersion);
        this.deploymentVersion = blankToNull(builder.deploymentVersion);
        this.enabled = builder.enabled;
        this.draining = builder.draining;
        this.attributes = immutableMapCopy(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAdapterNodeId() {
        return adapterNodeId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public String getDeploymentVersion() {
        return deploymentVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDraining() {
        return draining;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeGroupBindingRegistration that)) return false;
        return enabled == that.enabled
                && draining == that.draining
                && Objects.equals(adapterNodeId, that.adapterNodeId)
                && Objects.equals(workerGroupId, that.workerGroupId)
                && Objects.equals(pluginVersion, that.pluginVersion)
                && Objects.equals(deploymentVersion, that.deploymentVersion)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(adapterNodeId, workerGroupId, pluginVersion, deploymentVersion, enabled, draining, attributes);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, String> immutableMapCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = blankToNull(entry.getKey());
            String value = blankToNull(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(normalized);
    }

    public static final class Builder {
        private String adapterNodeId;
        private String workerGroupId;
        private String pluginVersion;
        private String deploymentVersion;
        private boolean enabled = true;
        private boolean draining;
        private Map<String, String> attributes = Collections.emptyMap();

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

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder draining(boolean draining) {
            this.draining = draining;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public NodeGroupBindingRegistration build() {
            return new NodeGroupBindingRegistration(this);
        }
    }
}
