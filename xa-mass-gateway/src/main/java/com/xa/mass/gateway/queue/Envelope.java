package com.xa.mass.gateway.queue;

public class Envelope {
    private String rawJson;     // 原始 JSON 消息
    private String workerId;
    private String connRole;
    private String traceId;     // 可选，用于日志追踪
    private long receivedAt;
    private String project;     // 所属项目名，如 WhatsApp、Telegram

    // 默认 project 可直接在构造器或 builder 设置
    public Envelope() {
        this.project = "RCS";
    }

    private Envelope(Builder builder) {
        this.rawJson = builder.rawJson;
        this.workerId = builder.workerId;
        this.connRole = builder.connRole;
        this.traceId = builder.traceId;
        this.receivedAt = builder.receivedAt;
        this.project = builder.project != null ? builder.project : "RCS";
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
        return connRole;
    }

    public void setConnRole(String connRole) {
        this.connRole = connRole;
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
        return project != null ? project : "RCS";
    }

    public void setProject(String project) {
        this.project = project;
    }

    @Override
    public String toString() {
        return "Envelope{" +
                "workerId='" + workerId + '\'' +
                ", connRole='" + connRole + '\'' +
                ", traceId='" + traceId + '\'' +
                ", receivedAt=" + receivedAt +
                ", project='" + getProject() + '\'' +
                ", rawJson=" + (rawJson != null ? rawJson.substring(0, Math.min(100, rawJson.length())) + "..." : null) +
                '}';
    }

    // ----------- Builder -----------
    public static class Builder {
        private String rawJson;
        private String workerId;
        private String connRole;
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
