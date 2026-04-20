package com.xa.mass.base.enums.task;

/**
 * Task lifecycle state.
 *
 * <p>Mainline flow:
 * NEW -> READY/BLOCKED -> RUNNING/PAUSED/BLOCKED -> TERMINAL
 *
 * <p>Pause/resume path:
 * NEW -> READY -> PAUSED -> READY -> RUNNING -> TERMINAL
 */
public enum TaskStatus {
    NEW("New"),
    BLOCKED("Blocked"),
    READY("Ready"),
    RUNNING("Running"),
    PAUSED("Paused"),
    TERMINAL("Terminal");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns whether the current state may transition to the target state.
     */
    public boolean canTransitionTo(TaskStatus targetStatus) {
        return switch (this) {
            case NEW -> targetStatus == READY || targetStatus == BLOCKED || targetStatus == TERMINAL;
            case BLOCKED -> targetStatus == READY || targetStatus == TERMINAL;
            case READY -> targetStatus == RUNNING || targetStatus == PAUSED || targetStatus == BLOCKED || targetStatus == TERMINAL;
            case RUNNING -> targetStatus == BLOCKED || targetStatus == PAUSED || targetStatus == TERMINAL;
            case PAUSED -> targetStatus == READY || targetStatus == TERMINAL;
            case TERMINAL -> false;
        };
    }

    public boolean isFinal() {
        return this == TERMINAL;
    }

    public boolean isSchedulable() {
        return this == READY;
    }

    public boolean isRunning() {
        return this == RUNNING;
    }

    public boolean isBlocked() {
        return this == BLOCKED;
    }

    public boolean isPaused() {
        return this == PAUSED;
    }

    /**
     * Returns whether the task may still accept runtime actions such as append.
     */
    public boolean isActive() {
        return this == READY || this == RUNNING;
    }
}
