package com.xa.mass.base.enums.assignment;

/**
 * Assignment record categories used by diagnostics and audit snapshots.
 */
public enum AssignmentType {
    WORKER_ASSIGN("Worker assignment"),
    MSG_ASSIGN("Message assignment");

    private final String description;

    AssignmentType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
