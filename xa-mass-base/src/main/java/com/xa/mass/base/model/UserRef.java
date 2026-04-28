package com.xa.mass.base.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal business-user binding carried by core aggregates.
 *
 * <p>This is intentionally a reference object, not a full IAM user model.
 */
public class UserRef {

    private String userId;
    private String displayName;
    private String email;
    private Map<String, String> attributes = Collections.emptyMap();

    public UserRef() {
    }

    public UserRef(String userId) {
        this.userId = requireUserId(userId);
    }

    public UserRef(String userId, String displayName, String email) {
        this.userId = requireUserId(userId);
        this.displayName = displayName;
        this.email = email;
    }

    public static UserRef of(String userId) {
        return new UserRef(userId);
    }

    public static String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = requireUserId(userId);
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            this.attributes = Collections.emptyMap();
            return;
        }
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public boolean hasUserId() {
        return userId != null && !userId.isBlank();
    }

    @Override
    public String toString() {
        return userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserRef userRef)) {
            return false;
        }
        return Objects.equals(userId, userRef.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
