package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-native adapter node registration endpoint declaration.
 */
public final class AdapterNodeRegistration {

    private final String adapterNodeId;
    private final String adapterType;
    private final String adapterVersion;
    private final String endpointId;
    private final boolean enabled;
    private final boolean online;
    private final Map<String, String> attributes;

    private AdapterNodeRegistration(Builder builder) {
        this.adapterNodeId = requireNonBlank(builder.adapterNodeId, "adapterNodeId");
        this.adapterType = blankToNull(builder.adapterType);
        this.adapterVersion = blankToNull(builder.adapterVersion);
        this.endpointId = blankToNull(builder.endpointId);
        this.enabled = builder.enabled;
        this.online = builder.online;
        this.attributes = immutableMapCopy(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAdapterNodeId() {
        return adapterNodeId;
    }

    public String getAdapterType() {
        return adapterType;
    }

    public String getAdapterVersion() {
        return adapterVersion;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOnline() {
        return online;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AdapterNodeRegistration that)) return false;
        return enabled == that.enabled
                && online == that.online
                && Objects.equals(adapterNodeId, that.adapterNodeId)
                && Objects.equals(adapterType, that.adapterType)
                && Objects.equals(adapterVersion, that.adapterVersion)
                && Objects.equals(endpointId, that.endpointId)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(adapterNodeId, adapterType, adapterVersion, endpointId, enabled, online, attributes);
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
        private String adapterType;
        private String adapterVersion;
        private String endpointId;
        private boolean enabled = true;
        private boolean online = true;
        private Map<String, String> attributes = Collections.emptyMap();

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

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder online(boolean online) {
            this.online = online;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public AdapterNodeRegistration build() {
            return new AdapterNodeRegistration(this);
        }
    }
}
