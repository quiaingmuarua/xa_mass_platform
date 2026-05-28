package com.xa.mass.client.worker;

public record WorkerCommandPollRequest(Integer maxCommands) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer maxCommands;

        private Builder() {
        }

        public Builder maxCommands(Integer maxCommands) {
            this.maxCommands = maxCommands;
            return this;
        }

        public WorkerCommandPollRequest build() {
            return new WorkerCommandPollRequest(maxCommands);
        }
    }
}
