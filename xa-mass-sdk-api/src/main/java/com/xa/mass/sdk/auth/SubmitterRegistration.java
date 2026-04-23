package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing submitter registration contract.
 *
 * <p>This is the minimal control-plane resource for API-key or service-account
 * style task submission. It is intentionally lightweight so security policies
 * can evolve without changing the SDK-facing task model.
 */
public final class SubmitterRegistration {

    private final String principalId;
    private final String credential;
    private final String userId;
    private final String projectScope;
    private final boolean enabled;
    private final Map<String, String> attributes;

    private SubmitterRegistration(Builder builder) {
        this.principalId = requireNonBlank(builder.principalId, "principalId");
        this.credential = requireNonBlank(builder.credential, "credential");
        this.userId = blankToNull(builder.userId);
        this.projectScope = blankToNull(builder.projectScope);
        this.enabled = builder.enabled;
        if (builder.attributes == null || builder.attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getCredential() {
        return credential;
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

    public TaskSubmitterContext toSubmitterContext() {
        return new TaskSubmitterContext(principalId, userId, projectScope, attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubmitterRegistration that)) return false;
        return enabled == that.enabled
                && Objects.equals(principalId, that.principalId)
                && Objects.equals(credential, that.credential)
                && Objects.equals(userId, that.userId)
                && Objects.equals(projectScope, that.projectScope)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalId, credential, userId, projectScope, enabled, attributes);
    }

    @Override
    public String toString() {
        return "SubmitterRegistration{" +
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

    public static final class Builder {
        private String principalId;
        private String credential;
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

        public Builder credential(String credential) {
            this.credential = credential;
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

        public SubmitterRegistration build() {
            return new SubmitterRegistration(this);
        }
    }
}
