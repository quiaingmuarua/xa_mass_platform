package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Public read model for a registered submitter.
 *
 * <p>Credentials are accepted only on {@link SubmitterRegistration}. Query
 * operations should expose this metadata shape so SDK callers do not
 * accidentally leak API keys or service-account tokens.
 */
public final class SubmitterMetadata {

    private final String principalId;
    private final String userId;
    private final String projectScope;
    private final boolean enabled;
    private final Map<String, String> attributes;

    private SubmitterMetadata(Builder builder) {
        this.principalId = requireNonBlank(builder.principalId, "principalId");
        this.userId = blankToNull(builder.userId);
        this.projectScope = blankToNull(builder.projectScope);
        this.enabled = builder.enabled;
        this.attributes = immutableAttributes(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SubmitterMetadata from(SubmitterRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        return builder()
                .principalId(registration.getPrincipalId())
                .userId(registration.getUserId())
                .projectScope(registration.getProjectScope())
                .enabled(registration.isEnabled())
                .attributes(registration.getAttributes())
                .build();
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getUserId() {
        return userId;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubmitterMetadata that)) return false;
        return enabled == that.enabled
                && Objects.equals(principalId, that.principalId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(projectScope, that.projectScope)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalId, userId, projectScope, enabled, attributes);
    }

    @Override
    public String toString() {
        return "SubmitterMetadata{" +
                "principalId='" + principalId + '\'' +
                ", userId='" + userId + '\'' +
                ", projectScope='" + projectScope + '\'' +
                ", enabled=" + enabled +
                ", attributes=" + attributes +
                '}';
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, String> immutableAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            String key = blankToNull(entry.getKey());
            String value = entry.getValue();
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return normalized.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(normalized);
    }

    public static final class Builder {
        private String principalId;
        private String userId;
        private String projectScope;
        private boolean enabled = true;
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder principalId(String principalId) {
            this.principalId = principalId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder projectScope(String projectScope) {
            this.projectScope = projectScope;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public SubmitterMetadata build() {
            return new SubmitterMetadata(this);
        }
    }
}
