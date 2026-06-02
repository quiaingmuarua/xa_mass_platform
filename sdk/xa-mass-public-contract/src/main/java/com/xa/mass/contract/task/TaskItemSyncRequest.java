package com.xa.mass.contract.task;

import com.xa.mass.contract.UnknownFieldRequest;

public class TaskItemSyncRequest extends UnknownFieldRequest {
    private String eventCode;
    private Object item;
    private Long timeoutMs;
    private String clientRequestId;

    public TaskItemSyncRequest() {
    }

    public TaskItemSyncRequest(String eventCode, Object item, Long timeoutMs, String clientRequestId) {
        this.eventCode = eventCode;
        this.item = item;
        this.timeoutMs = timeoutMs;
        this.clientRequestId = clientRequestId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public Object getItem() {
        return item;
    }

    public void setItem(Object item) {
        this.item = item;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String eventCode() {
        return eventCode;
    }

    public Object item() {
        return item;
    }

    public Long timeoutMs() {
        return timeoutMs;
    }

    public String clientRequestId() {
        return clientRequestId;
    }

    public static final class Builder {
        private String eventCode;
        private Object item;
        private Long timeoutMs;
        private String clientRequestId;

        private Builder() {
        }

        public Builder eventCode(String eventCode) {
            this.eventCode = eventCode;
            return this;
        }

        public Builder item(Object item) {
            this.item = item;
            return this;
        }

        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder clientRequestId(String clientRequestId) {
            this.clientRequestId = clientRequestId;
            return this;
        }

        public TaskItemSyncRequest build() {
            return new TaskItemSyncRequest(eventCode, item, timeoutMs, clientRequestId);
        }
    }
}
