package com.xa.mass.base.event;

/**
 * Descriptive target-scope metadata for event catalogs.
 *
 * <p>This metadata must not bypass the current owner path for worker commands,
 * worker state, operator control, or task-engine behavior.
 */
public enum TargetScope {
    WORKER,
    TASK_ENGINE,
    OPERATOR,
    WORKER_MANAGER
}
