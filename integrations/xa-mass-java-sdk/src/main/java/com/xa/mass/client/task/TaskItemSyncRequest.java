package com.xa.mass.client.task;

public record TaskItemSyncRequest(String eventCode, Object item, Long timeoutMs, String clientRequestId) {
    public static Builder builder() {
        return new Builder();
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
