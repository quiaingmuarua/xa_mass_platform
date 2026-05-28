package com.xa.mass.client.task;

import java.util.LinkedHashMap;
import java.util.Map;

public record TaskUpdateRequest(String userId, String project, Map<String, Object> sharedConfig) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String project;
        private Map<String, Object> sharedConfig = new LinkedHashMap<>();

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
            this.sharedConfig = sharedConfig == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sharedConfig);
            return this;
        }

        public TaskUpdateRequest build() {
            return new TaskUpdateRequest(userId, project, Map.copyOf(sharedConfig));
        }
    }
}
