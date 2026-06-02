package com.xa.mass.client.task;

import java.util.Map;

public record TaskCommandRequest(TaskCommand command, String reason, Map<String, Object> options) {
    public static TaskCommandRequest seal() {
        return new TaskCommandRequest(TaskCommand.SEAL, null, Map.of());
    }

    public static TaskCommandRequest approve() {
        return new TaskCommandRequest(TaskCommand.APPROVE, null, Map.of());
    }

    public static Builder builder(TaskCommand command) {
        return new Builder(command);
    }

    public static final class Builder {
        private final TaskCommand command;
        private String reason;
        private Map<String, Object> options = Map.of();

        private Builder(TaskCommand command) {
            this.command = command;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options == null ? Map.of() : Map.copyOf(options);
            return this;
        }

        public TaskCommandRequest build() {
            return new TaskCommandRequest(command, reason, options);
        }
    }
}
