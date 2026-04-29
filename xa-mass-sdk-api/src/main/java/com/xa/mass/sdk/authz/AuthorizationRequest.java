package com.xa.mass.sdk.authz;

import com.xa.mass.sdk.auth.PrincipalContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-neutral authorization request.
 */
public final class AuthorizationRequest {

    private final PrincipalContext principal;
    private final PlatformResourceType resourceType;
    private final PlatformAction action;
    private final String project;
    private final String eventCode;
    private final String workerId;
    private final Map<String, Object> resourceAttributes;

    private AuthorizationRequest(Builder builder) {
        this.principal = builder.principal;
        this.resourceType = Objects.requireNonNull(builder.resourceType, "resourceType");
        this.action = Objects.requireNonNull(builder.action, "action");
        this.project = normalizeString(builder.project);
        this.eventCode = normalizeString(builder.eventCode);
        this.workerId = normalizeString(builder.workerId);
        this.resourceAttributes = immutableMapCopy(builder.resourceAttributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public PrincipalContext getPrincipal() {
        return principal;
    }

    public PlatformResourceType getResourceType() {
        return resourceType;
    }

    public PlatformAction getAction() {
        return action;
    }

    public String getProject() {
        return project;
    }

    public String getEventCode() {
        return eventCode;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Map<String, Object> getResourceAttributes() {
        return resourceAttributes;
    }

    public String getStringAttribute(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Object value = resourceAttributes.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, Object> immutableMapCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String normalizeString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static final class Builder {
        private PrincipalContext principal;
        private PlatformResourceType resourceType;
        private PlatformAction action;
        private String project;
        private String eventCode;
        private String workerId;
        private Map<String, Object> resourceAttributes = Collections.emptyMap();

        private Builder() {
        }

        public Builder principal(PrincipalContext principal) {
            this.principal = principal;
            return this;
        }

        public Builder resourceType(PlatformResourceType resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder action(PlatformAction action) {
            this.action = action;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder resourceAttributes(Map<String, Object> resourceAttributes) {
            this.resourceAttributes = resourceAttributes == null ? Collections.emptyMap() : resourceAttributes;
            return this;
        }

        public AuthorizationRequest build() {
            return new AuthorizationRequest(this);
        }
    }
}
