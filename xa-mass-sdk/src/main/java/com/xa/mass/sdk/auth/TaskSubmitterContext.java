package com.xa.mass.sdk.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Future-facing submitter identity context resolved from an API key or other
 * credential.
 */
public final class TaskSubmitterContext {

    private final String principalId;
    private final String userId;
    private final String projectScope;
    private final Map<String, String> attributes;

    public TaskSubmitterContext(String principalId,
                                String userId,
                                String projectScope,
                                Map<String, String> attributes) {
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.userId = userId;
        this.projectScope = projectScope;
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

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
