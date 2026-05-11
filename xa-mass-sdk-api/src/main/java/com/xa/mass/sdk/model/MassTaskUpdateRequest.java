package com.xa.mass.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-native partial task update contract for editable task metadata.
 *
 * <p>This keeps update callers off the mutable core {@code Task} model while
 * making the allowed patch fields explicit.
 */
public final class MassTaskUpdateRequest {

    private final String userId;
    private final String project;
    private final Map<String, Object> sharedConfig;

    private MassTaskUpdateRequest(Builder builder) {
        this.userId = normalizeString(builder.userId);
        this.project = normalizeString(builder.project);
        this.sharedConfig = unmodifiableMapCopy(builder.sharedConfig);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getProject() {
        return project;
    }

    public Map<String, Object> getSharedConfig() {
        return sharedConfig;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MassTaskUpdateRequest that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(project, that.project)
                && Objects.equals(sharedConfig, that.sharedConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, project, sharedConfig);
    }

    @Override
    public String toString() {
        return "MassTaskUpdateRequest{" +
                "userId='" + userId + '\'' +
                ", project='" + project + '\'' +
                ", sharedConfig=" + sharedConfig +
                '}';
    }

    public static final class Builder {
        private String userId;
        private String project;
        private Map<String, Object> sharedConfig = Collections.emptyMap();

        private Builder() {
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder sharedConfig(Map<String, Object> sharedConfig) {
            this.sharedConfig = sharedConfig;
            return this;
        }

        public MassTaskUpdateRequest build() {
            return new MassTaskUpdateRequest(this);
        }
    }

    private static Map<String, Object> unmodifiableMapCopy(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
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
}
