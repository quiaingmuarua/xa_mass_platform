package com.xa.mass.base.event;

/**
 * Descriptive caller expectation for event responses.
 *
 * <p>This metadata does not choose result-convergence or task-finality paths.
 */
public enum ResponseMode {
    NONE,
    ACK,
    FINAL_RESULT,
    STREAM
}
