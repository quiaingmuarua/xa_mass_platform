package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Public read model for a projected credential principal.
 */
public final class CredentialPrincipalProfile {

    private final String principalId;
    private final PrincipalType principalType;
    private final String keyPrefix;
    private final String userId;
    private final String projectScope;
    private final List<String> permissions;
    private final List<String> projectScopes;
    private final List<String> eventScopes;
    private final boolean enabled;
    private final Map<String, String> attributes;

    private CredentialPrincipalProfile(Builder builder) {
        this.principalId = requireNonBlank(builder.principalId, "principalId");
        this.principalType = builder.principalType == null ? PrincipalType.SERVICE : builder.principalType;
        this.keyPrefix = blankToNull(builder.keyPrefix);
        this.userId = blankToNull(builder.userId);
        this.projectScope = blankToNull(builder.projectScope);
        this.permissions = immutableStringList(builder.permissions);
        this.projectScopes = immutableStringList(builder.projectScopes);
        this.eventScopes = immutableStringList(builder.eventScopes);
        this.enabled = builder.enabled;
        this.attributes = immutableAttributes(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CredentialPrincipalProfile from(CredentialPrincipalRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        return builder()
                .principalId(registration.getPrincipalId())
                .principalType(registration.getPrincipalType())
                .keyPrefix(registration.getKeyPrefix())
                .userId(registration.getUserId())
                .projectScope(registration.getProjectScope())
                .permissions(registration.getPermissions())
                .projectScopes(registration.getProjectScopes())
                .eventScopes(registration.getEventScopes())
                .enabled(registration.isEnabled())
                .attributes(registration.getAttributes())
                .build();
    }

    public String getPrincipalId() {
        return principalId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getUserId() {
        return userId;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public List<String> getProjectScopes() {
        return projectScopes;
    }

    public List<String> getEventScopes() {
        return eventScopes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public PrincipalContext toPrincipalContext() {
        return PrincipalContext.builder()
                .principalId(principalId)
                .principalType(principalType)
                .userId(userId)
                .projectScope(projectScope)
                .permissions(permissions)
                .projectScopes(projectScopes)
                .eventScopes(eventScopes)
                .attributes(attributes)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CredentialPrincipalProfile that)) return false;
        return enabled == that.enabled
                && Objects.equals(principalId, that.principalId)
                && principalType == that.principalType
                && Objects.equals(keyPrefix, that.keyPrefix)
                && Objects.equals(userId, that.userId)
                && Objects.equals(projectScope, that.projectScope)
                && Objects.equals(permissions, that.permissions)
                && Objects.equals(projectScopes, that.projectScopes)
                && Objects.equals(eventScopes, that.eventScopes)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalId, principalType, keyPrefix, userId, projectScope, permissions, projectScopes, eventScopes, enabled, attributes);
    }

    @Override
    public String toString() {
        return "CredentialPrincipalProfile{" +
                "principalId='" + principalId + '\'' +
                ", principalType=" + principalType +
                ", keyPrefix='" + keyPrefix + '\'' +
                ", userId='" + userId + '\'' +
                ", projectScope='" + projectScope + '\'' +
                ", permissions=" + permissions +
                ", projectScopes=" + projectScopes +
                ", eventScopes=" + eventScopes +
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

    private static List<String> immutableStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String normalizedValue = blankToNull(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue);
            }
        }
        return normalized.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(normalized));
    }

    public static final class Builder {
        private String principalId;
        private PrincipalType principalType = PrincipalType.SERVICE;
        private String keyPrefix;
        private String userId;
        private String projectScope;
        private List<String> permissions = List.of();
        private List<String> projectScopes = List.of();
        private List<String> eventScopes = List.of();
        private boolean enabled = true;
        private Map<String, String> attributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder principalId(String principalId) {
            this.principalId = principalId;
            return this;
        }

        public Builder principalType(PrincipalType principalType) {
            this.principalType = principalType;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
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

        public Builder permissions(List<String> permissions) {
            this.permissions = permissions != null ? permissions : List.of();
            return this;
        }

        public Builder projectScopes(List<String> projectScopes) {
            this.projectScopes = projectScopes != null ? projectScopes : List.of();
            return this;
        }

        public Builder eventScopes(List<String> eventScopes) {
            this.eventScopes = eventScopes != null ? eventScopes : List.of();
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

        public CredentialPrincipalProfile build() {
            return new CredentialPrincipalProfile(this);
        }
    }
}
