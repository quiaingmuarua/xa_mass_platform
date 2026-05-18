package com.xa.mass.base.event;

/**
 * Descriptive delivery acknowledgement expectation for event catalogs.
 *
 * <p>This metadata does not choose transport acknowledgement, command status,
 * result convergence, or task finality paths.</p>
 */
public enum DeliveryAcknowledgementMode {
    NONE,
    HANDLER_ACCEPTED,
    DELIVERY_ACCEPTED;

    public static DeliveryAcknowledgementMode fromResponseMode(ResponseMode responseMode) {
        if (responseMode == ResponseMode.ACK) {
            return HANDLER_ACCEPTED;
        }
        return NONE;
    }
}
