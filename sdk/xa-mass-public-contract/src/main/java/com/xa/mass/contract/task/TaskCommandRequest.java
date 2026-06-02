package com.xa.mass.contract.task;

import com.xa.mass.contract.UnknownFieldRequest;

import java.util.Map;

public class TaskCommandRequest extends UnknownFieldRequest {
    private String command;
    private String reason;
    private Map<String, Object> options;

    public TaskCommandRequest() {
    }

    public TaskCommandRequest(TaskCommand command, String reason, Map<String, Object> options) {
        this(command == null ? null : command.name(), reason, options);
    }

    public TaskCommandRequest(String command, String reason, Map<String, Object> options) {
        this.command = command;
        this.reason = reason;
        this.options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static TaskCommandRequest seal() {
        return new TaskCommandRequest(TaskCommand.SEAL, null, Map.of());
    }

    public static TaskCommandRequest approve() {
        return new TaskCommandRequest(TaskCommand.APPROVE, null, Map.of());
    }

    public static Builder builder(TaskCommand command) {
        return new Builder(command == null ? null : command.name());
    }

    public static Builder builder(String command) {
        return new Builder(command);
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public String command() {
        return command;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> options() {
        return options;
    }

    public static final class Builder {
        private final String command;
        private String reason;
        private Map<String, Object> options = Map.of();

        private Builder(String command) {
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
