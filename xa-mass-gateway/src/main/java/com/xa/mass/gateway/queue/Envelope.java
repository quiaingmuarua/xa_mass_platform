package com.xa.mass.gateway.queue;

import com.xa.mass.transport.WorkerEndpointRoles;

public class Envelope {
    private String rawJson;     // 原始 JSON 消息
    private String workerId;
    private String connRole;
    private String eventCode;   // 可选的全局能力标识元数据；不是连接路由键
    private String traceId;     // 可选，用于日志追踪
    private long receivedAt;
    private String project;     // 可选 scope 元数据；不再提供默认项目值

    public Envelope() {
    }

    private Envelope(Builder builder) {
        this.rawJson = builder.rawJson;
        this.workerId = builder.workerId;
        this.connRole = normalizeConnRole(builder.connRole);
        this.eventCode = builder.eventCode;
        this.traceId = builder.traceId;
        this.receivedAt = builder.receivedAt;
        this.project = normalize(builder.project);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ----------- Getters/Setters -----------
    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getConnRole() {
        return normalizeConnRole(connRole);
    }

    public void setConnRole(String connRole) {
        this.connRole = normalizeConnRole(connRole);
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(long receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = normalize(project);
    }

    @Override
    public String toString() {
        return "Envelope{" +
                "workerId='" + workerId + '\'' +
                ", connRole='" + connRole + '\'' +
                ", eventCode='" + eventCode + '\'' +
                ", traceId='" + traceId + '\'' +
                ", receivedAt=" + receivedAt +
                ", project='" + project + '\'' +
                ", rawJson=" + (rawJson != null ? rawJson.substring(0, Math.min(100, rawJson.length())) + "..." : null) +
                '}';
    }

    private static String normalize(String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        return project.trim();
    }

    private static String normalizeConnRole(String connRole) {
        if (connRole == null || connRole.isBlank()) {
            return WorkerEndpointRoles.TASK_DISPATCH;
        }
        return connRole.trim();
    }

    // ----------- Builder -----------
    public static class Builder {
        private String rawJson;
        private String workerId;
        private String connRole;
        private String eventCode;
        private String traceId;
        private long receivedAt;
        private String project;

        public Builder rawJson(String rawJson) {
            this.rawJson = rawJson;
            return this;
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder connRole(String connRole) {
            this.connRole = connRole;
            return this;
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder receivedAt(long receivedAt) {
            this.receivedAt = receivedAt;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Envelope build() {
            return new Envelope(this);
        }
    }
}
