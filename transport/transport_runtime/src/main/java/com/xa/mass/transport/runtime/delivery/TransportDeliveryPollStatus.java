package com.xa.mass.transport.runtime.delivery;

public enum TransportDeliveryPollStatus {
    /**
     * One or more envelopes were delivered for the requested queue key.
     */
    DELIVERED,
    /**
     * No envelopes were delivered before timeout elapsed.
     */
    EMPTY,
    /**
     * Caller provided an invalid adapter/route/maxItems request.
     */
    INVALID_REQUEST,
    /**
     * Synthesized by {@link TransportDeliveryService} when the polling thread
     * is interrupted while the underlying store throws
     * {@link InterruptedException}. Store implementations should not return
     * this status directly.
     */
    INTERRUPTED,
    /**
     * The store is currently unavailable for poll work.
     */
    UNAVAILABLE,
    /**
     * The store has been shut down and should be treated as unavailable.
     */
    SHUTDOWN
}
