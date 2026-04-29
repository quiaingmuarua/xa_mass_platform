package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unified authenticated caller context used across operator, SDK submitter,
 * worker, and event-runtime entry points.
 */
public final class PrincipalContext {

    public static final String WILDCARD_SCOPE = "*";
    public static final String TASK_CREATE_PERMISSION = "task:create";
    public static final String EXTERNAL_WORKER_PERMISSION = "worker:poll";

    private final String principalId;
    private final PrincipalType principalType;
    private final String userId;
    private final String projectScope;
    private final List<String> permissions;
    private final List<String> projectScopes;
    private final List<String> eventScopes;
    private final Map<String, String> attributes;

    public PrincipalContext(String principalId,
                            String userId,
                            String projectScope,
                            Map<String, String> attributes) {
        this(principalId,
                PrincipalType.SERVICE,
                userId,
                projectScope,
                List.of(TASK_CREATE_PERMISSION),
                projectScope == null || projectScope.isBlank() ? List.of() : List.of(projectScope),
                List.of(),
                attributes);
    }

    public PrincipalContext(String principalId,
                            String userId,
                            String projectScope,
                            List<String> permissions,
                            List<String> projectScopes,
                            List<String> eventScopes,
                            Map<String, String> attributes) {
        this(principalId,
                PrincipalType.SERVICE,
                userId,
                projectScope,
                permissions,
                projectScopes,
                eventScopes,
                attributes);
    }

    public PrincipalContext(String principalId,
                            PrincipalType principalType,
                            String userId,
                            String projectScope,
                            List<String> permissions,
                            List<String> projectScopes,
                            List<String> eventScopes,
                            Map<String, String> attributes) {
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.principalType = principalType == null ? PrincipalType.SERVICE : principalType;
        this.userId = userId;
        this.projectScope = projectScope;
        this.permissions = immutableStringList(permissions);
        this.projectScopes = immutableStringList(projectScopes);
        this.eventScopes = immutableStringList(eventScopes);
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static PrincipalContext internalService(String principalId, String userId) {
        return builder()
                .principalId(principalId)
                .principalType(PrincipalType.SERVICE)
                .userId(userId)
                .permissions(List.of(WILDCARD_SCOPE))
                .projectScopes(List.of(WILDCARD_SCOPE))
                .eventScopes(List.of(WILDCARD_SCOPE))
                .build();
    }

    public String getPrincipalId() {
        return principalId;
    }

    public PrincipalType getPrincipalType() {
        return principalType;
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

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        String normalized = permission.trim();
        return permissions.contains(WILDCARD_SCOPE) || permissions.contains(normalized);
    }

    public boolean allowsProject(String projectCode) {
        return allowsScope(projectScopes, projectCode);
    }

    public boolean allowsEvent(String eventCode) {
        return allowsScope(eventScopes, eventCode);
    }

    private static boolean allowsScope(List<String> scopes, String value) {
        if (scopes.isEmpty()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        return scopes.contains(WILDCARD_SCOPE) || scopes.contains(value.trim());
    }

    private static List<String> immutableStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return normalized.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(List.copyOf(normalized));
    }

    public static final class Builder {
        private String principalId;
        private PrincipalType principalType = PrincipalType.SERVICE;
        private String userId;
        private String projectScope;
        private List<String> permissions = List.of();
        private List<String> projectScopes = List.of();
        private List<String> eventScopes = List.of();
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

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
            return this;
        }

        public PrincipalContext build() {
            return new PrincipalContext(
                    Objects.requireNonNull(principalId, "principalId"),
                    principalType,
                    userId,
                    projectScope,
                    permissions,
                    projectScopes,
                    eventScopes,
                    attributes
            );
        }
    }
}
