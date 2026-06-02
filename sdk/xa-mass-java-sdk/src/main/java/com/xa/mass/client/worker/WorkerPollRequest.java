package com.xa.mass.client.worker;

public record WorkerPollRequest(Integer maxMessages, Long timeoutMs) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer maxMessages;
        private Long timeoutMs;

        private Builder() {
        }

        public Builder maxMessages(Integer maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public WorkerPollRequest build() {
            return new WorkerPollRequest(maxMessages, timeoutMs);
        }
    }
}
