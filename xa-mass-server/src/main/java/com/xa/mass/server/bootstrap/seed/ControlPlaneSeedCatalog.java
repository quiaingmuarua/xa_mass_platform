package com.xa.mass.server.bootstrap.seed;

import java.util.Collections;
import java.util.List;
import java.util.Map;

final class ControlPlaneSeedCatalog {
    private List<ProjectSeed> projects = List.of();
    private List<EventSeed> events = List.of();
    private List<ApiKeySeed> apiKeys = List.of();
    private List<ApiKeySeed> submitters = List.of();

    List<ProjectSeed> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectSeed> projects) {
        this.projects = projects != null ? projects : List.of();
    }

    List<EventSeed> getEvents() {
        return events;
    }

    public void setEvents(List<EventSeed> events) {
        this.events = events != null ? events : List.of();
    }

    List<ApiKeySeed> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeySeed> apiKeys) {
        this.apiKeys = apiKeys != null ? apiKeys : List.of();
    }

    List<ApiKeySeed> getSubmitters() {
        return submitters;
    }

    public void setSubmitters(List<ApiKeySeed> submitters) {
        this.submitters = submitters != null ? submitters : List.of();
    }

    static final class ProjectSeed {
        private int count = 1;
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private List<String> eventCodes = List.of();

        int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        List<String> getEventCodes() {
            return eventCodes;
        }

        public void setEventCodes(List<String> eventCodes) {
            this.eventCodes = eventCodes != null ? eventCodes : List.of();
        }
    }

    static final class EventSeed {
        private int count = 1;
        private String code;
        private String name;
        private String description;
        private List<String> payloadTypes = List.of();
        private List<String> taskModes = List.of();
        private boolean enabled = true;
        private String defaultRoutingCode;
        private List<String> projectCodes = List.of();

        int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        List<String> getPayloadTypes() {
            return payloadTypes;
        }

        public void setPayloadTypes(List<String> payloadTypes) {
            this.payloadTypes = payloadTypes != null ? payloadTypes : List.of();
        }

        List<String> getTaskModes() {
            return taskModes;
        }

        public void setTaskModes(List<String> taskModes) {
            this.taskModes = taskModes != null ? taskModes : List.of();
        }

        boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        String getDefaultRoutingCode() {
            return defaultRoutingCode;
        }

        public void setDefaultRoutingCode(String defaultRoutingCode) {
            this.defaultRoutingCode = defaultRoutingCode;
        }

        List<String> getProjectCodes() {
            return projectCodes;
        }

        public void setProjectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : List.of();
        }
    }

    static final class ApiKeySeed {
        private int count = 1;
        private String principalId;
        private String rawSecret;
        private String credential;
        private String keyPrefix;
        private String createdForUserId;
        private List<String> permissions = List.of();
        private List<String> projectScopes = List.of();
        private List<String> eventScopes = List.of();
        private String createdBy = "system-seed";
        private boolean devOnly;
        private Map<String, String> attributes = Collections.emptyMap();

        int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        String getPrincipalId() {
            return principalId;
        }

        public void setPrincipalId(String principalId) {
            this.principalId = principalId;
        }

        String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        String getRawSecret() {
            return rawSecret;
        }

        public void setRawSecret(String rawSecret) {
            this.rawSecret = rawSecret;
        }

        String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        String getCreatedForUserId() {
            return createdForUserId;
        }

        public void setCreatedForUserId(String createdForUserId) {
            this.createdForUserId = createdForUserId;
        }

        public void setUserId(String userId) {
            this.createdForUserId = userId;
        }

        public void setProjectScope(String projectScope) {
            if (projectScope != null && !projectScope.isBlank()) {
                this.projectScopes = List.of(projectScope);
            }
        }

        List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions != null ? permissions : List.of();
        }

        List<String> getProjectScopes() {
            return projectScopes;
        }

        public void setProjectScopes(List<String> projectScopes) {
            this.projectScopes = projectScopes != null ? projectScopes : List.of();
        }

        List<String> getEventScopes() {
            return eventScopes;
        }

        public void setEventScopes(List<String> eventScopes) {
            this.eventScopes = eventScopes != null ? eventScopes : List.of();
        }

        String getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
        }

        boolean isDevOnly() {
            return devOnly;
        }

        public void setDevOnly(boolean devOnly) {
            this.devOnly = devOnly;
        }

        Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
        }
    }
}
