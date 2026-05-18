package com.xa.mass.base.event;

/**
 * Descriptive convergence expectation for event catalogs.
 *
 * <p>This metadata does not choose the concrete owner that records final
 * result, command status, state projection, capability mutation, or stage
 * progress.</p>
 */
public enum EventConvergenceMode {
    NONE,
    FINAL_RESULT,
    STREAM;

    public static EventConvergenceMode fromResponseMode(ResponseMode responseMode) {
        return switch (responseMode == null ? ResponseMode.FINAL_RESULT : responseMode) {
            case FINAL_RESULT -> FINAL_RESULT;
            case STREAM -> STREAM;
            case NONE, ACK -> NONE;
        };
    }
}
