package com.xa.mass.base.enums.task;

/**
 * Kernel-level task contract.
 *
 * <p>This axis owns lifecycle, dispatch, terminal, and default query
 * expectations. It is separate from {@link TaskSourceType}, which describes
 * intake/source shape, and {@link TaskWorkloadClass}, which describes runtime
 * tuning intent.
 */
public enum TaskContract {
    SESSION,
    BATCH
}
