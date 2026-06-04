package com.xa.mass.api.sample;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SampleCatalogBootstrapRequest {

    private List<ProjectRegistration> projects = List.of();
    private List<EventRegistration> events = List.of();
    private List<SubmitterResource> submitters = List.of();

    public List<ProjectRegistration> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectRegistration> projects) {
        this.projects = projects != null ? projects : List.of();
    }

    public List<EventRegistration> getEvents() {
        return events;
    }

    public void setEvents(List<EventRegistration> events) {
        this.events = events != null ? events : List.of();
    }

    public List<SubmitterResource> getSubmitters() {
        return submitters;
    }

    public void setSubmitters(List<SubmitterResource> submitters) {
        this.submitters = submitters != null ? submitters : List.of();
    }

    public static class ProjectRegistration {
        private String code;
        private String name;
        private String description;
        private boolean enabled = true;
        private List<String> eventCodes = List.of();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getEventCodes() {
            return eventCodes;
        }

        public void setEventCodes(List<String> eventCodes) {
            this.eventCodes = eventCodes != null ? eventCodes : List.of();
        }
    }

    public static class EventRegistration {
        private String code;
        private String name;
        private String description;
        private List<String> payloadTypes = List.of();
        private List<String> taskModes = List.of();
        private boolean enabled = true;
        private String defaultRoutingCode;
        private List<String> projectCodes = List.of();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getPayloadTypes() {
            return payloadTypes;
        }

        public void setPayloadTypes(List<String> payloadTypes) {
            this.payloadTypes = payloadTypes != null ? payloadTypes : List.of();
        }

        public List<String> getTaskModes() {
            return taskModes;
        }

        public void setTaskModes(List<String> taskModes) {
            this.taskModes = taskModes != null ? taskModes : List.of();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultRoutingCode() {
            return defaultRoutingCode;
        }

        public void setDefaultRoutingCode(String defaultRoutingCode) {
            this.defaultRoutingCode = defaultRoutingCode;
        }

        public List<String> getProjectCodes() {
            return projectCodes;
        }

        public void setProjectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes != null ? projectCodes : List.of();
        }
    }

    public static class SubmitterResource {
        private String principalId;
        private String credential;
        private String keyPrefix;
        private String userId;
        private String projectScope;
        private List<String> permissions = List.of();
        private List<String> projectScopes = List.of();
        private List<String> eventScopes = List.of();
        private boolean enabled = true;
        private Map<String, String> attributes = Collections.emptyMap();

        public String getPrincipalId() {
            return principalId;
        }

        public void setPrincipalId(String principalId) {
            this.principalId = principalId;
        }

        public String getCredential() {
            return credential;
        }

        public void setCredential(String credential) {
            this.credential = credential;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getProjectScope() {
            return projectScope;
        }

        public void setProjectScope(String projectScope) {
            this.projectScope = projectScope;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions != null ? permissions : List.of();
        }

        public List<String> getProjectScopes() {
            return projectScopes;
        }

        public void setProjectScopes(List<String> projectScopes) {
            this.projectScopes = projectScopes != null ? projectScopes : List.of();
        }

        public List<String> getEventScopes() {
            return eventScopes;
        }

        public void setEventScopes(List<String> eventScopes) {
            this.eventScopes = eventScopes != null ? eventScopes : List.of();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, String> getAttributes() {
            return attributes;
        }

        public void setAttributes(Map<String, String> attributes) {
            this.attributes = attributes != null ? attributes : Collections.emptyMap();
        }
    }
}
