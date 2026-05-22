package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SDK-facing submitter registration contract.
 *
 * <p>This is the minimal control-plane resource for API-key or service-account
 * style task submission. It is intentionally lightweight so security policies
 * can evolve without changing the SDK-facing task model.
 */
public final class SubmitterRegistration {

    private final String principalId;
    private final PrincipalType principalType;
    private final String credential;
    private final String keyPrefix;
    private final String userId;
    private final String projectScope;
    private final List<String> permissions;
    private final List<String> projectScopes;
    private final List<String> eventScopes;
    private final boolean enabled;
    private final Map<String, String> attributes;

    private SubmitterRegistration(Builder builder) {
        this.principalId = requireNonBlank(builder.principalId, "principalId");
        this.principalType = builder.principalType == null ? PrincipalType.SERVICE : builder.principalType;
        this.credential = requireNonBlank(builder.credential, "credential");
        this.keyPrefix = blankToNull(builder.keyPrefix) != null
                ? blankToNull(builder.keyPrefix)
                : defaultKeyPrefix(this.credential);
        this.userId = blankToNull(builder.userId);
        this.projectScope = blankToNull(builder.projectScope);
        this.permissions = immutableStringList(builder.permissions, List.of(PrincipalContext.TASK_CREATE_PERMISSION));
        this.projectScopes = immutableStringList(
                !builder.projectScopes.isEmpty()
                        ? builder.projectScopes
                        : (this.projectScope == null ? List.of() : List.of(this.projectScope)),
                List.of()
        );
        this.eventScopes = immutableStringList(builder.eventScopes, List.of());
        this.enabled = builder.enabled;
        this.attributes = immutableAttributes(builder.attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPrincipalId() {
        return principalId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
    }

    public String getCredential() {
        return credential;
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
        return new PrincipalContext(
                principalId,
                principalType,
                userId,
                projectScope,
                permissions,
                projectScopes,
                eventScopes,
                attributes
        );
    }

    public SubmitterProfile toProfile() {
        return SubmitterProfile.from(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubmitterRegistration that)) return false;
        return enabled == that.enabled
                && Objects.equals(principalId, that.principalId)
                && principalType == that.principalType
                && Objects.equals(credential, that.credential)
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
        return Objects.hash(principalId, principalType, credential, keyPrefix, userId, projectScope, permissions, projectScopes, eventScopes, enabled, attributes);
    }

    @Override
    public String toString() {
        return "SubmitterRegistration{" +
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

    private static List<String> immutableStringList(List<String> values, List<String> defaultValues) {
        List<String> source = values == null || values.isEmpty() ? defaultValues : values;
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String normalizedValue = blankToNull(value);
            if (normalizedValue != null) {
                normalized.add(normalizedValue);
            }
        }
        return normalized.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(normalized));
    }

    private static String defaultKeyPrefix(String credential) {
        String normalized = requireNonBlank(credential, "credential");
        return normalized.substring(0, Math.min(8, normalized.length())) + "...";
    }

    public static final class Builder {
        private String principalId;
        private PrincipalType principalType = PrincipalType.SERVICE;
        private String credential;
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

        public Builder credential(String credential) {
            this.credential = credential;
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

        public SubmitterRegistration build() {
            return new SubmitterRegistration(this);
        }
    }
}
