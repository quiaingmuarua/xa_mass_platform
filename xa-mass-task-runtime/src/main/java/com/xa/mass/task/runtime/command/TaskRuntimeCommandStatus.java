package com.xa.mass.task.runtime.command;

public enum TaskRuntimeCommandStatus {
    APPLIED(true),
    ALREADY_APPLIED(true),
    CONFLICT(false),
    REJECTED(false);

    private final boolean accepted;

    TaskRuntimeCommandStatus(boolean accepted) {
        this.accepted = accepted;
    }

    public boolean accepted() {
        return accepted;
    }
}
