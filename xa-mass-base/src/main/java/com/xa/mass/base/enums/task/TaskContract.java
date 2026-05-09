package com.xa.mass.base.enums.task;

/**
 * Kernel-level task contract.
 *
 * <p>This axis owns lifecycle, dispatch, terminal, and default query
 * expectations. Intake/source shape is not kernel truth here. Runtime tuning
 * intent remains a separate concern in {@link TaskWorkloadClass}.
 */
public enum TaskContract {
    SESSION,
    BATCH
}
