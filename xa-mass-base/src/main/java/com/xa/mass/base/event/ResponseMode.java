package com.xa.mass.base.event;

/**
 * Compatibility summary of caller expectations for event responses.
 *
 * <p>New owner designs should prefer {@link DeliveryAcknowledgementMode} and
 * {@link EventConvergenceMode}. This metadata remains catalog-visible but does
 * not choose result-convergence, command status, stage, or task-finality paths.</p>
 */
public enum ResponseMode {
    NONE,
    ACK,
    FINAL_RESULT,
    STREAM
}
