package com.xa.mass.base.enums.task;

/**
 * Declares where a task's runnable work items come from.
 */
public enum TaskSourceType {
    BATCH,
    STREAM,
    FILE;

    public boolean allowsEmptyInitialInputs() {
        return this == STREAM || this == FILE;
    }
}
