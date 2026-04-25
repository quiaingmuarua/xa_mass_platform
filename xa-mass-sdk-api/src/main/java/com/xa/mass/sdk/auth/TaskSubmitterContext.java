package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Future-facing submitter identity context resolved from an API key or other
 * credential.
 */
public final class TaskSubmitterContext {

    public static final String WILDCARD_SCOPE = "*";
    public static final String TASK_CREATE_PERMISSION = "task:create";
    public static final String EXTERNAL_WORKER_PERMISSION = "worker:poll";

    private final String principalId;
    private final String userId;
    private final String projectScope;
    private final List<String> permissions;
    private final List<String> projectScopes;
    private final List<String> eventScopes;
    private final Map<String, String> attributes;

    public TaskSubmitterContext(String principalId,
                                String userId,
                                String projectScope,
                                Map<String, String> attributes) {
        this(principalId,
                userId,
                projectScope,
                List.of(TASK_CREATE_PERMISSION),
                projectScope == null || projectScope.isBlank() ? List.of() : List.of(projectScope),
                List.of(),
                attributes);
    }

    public TaskSubmitterContext(String principalId,
                                String userId,
                                String projectScope,
                                List<String> permissions,
                                List<String> projectScopes,
                                List<String> eventScopes,
                                Map<String, String> attributes) {
        this.principalId = Objects.requireNonNull(principalId, "principalId");
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

    public String getPrincipalId() {
        return principalId;
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
        return permission != null && permissions.contains(permission.trim());
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
}
