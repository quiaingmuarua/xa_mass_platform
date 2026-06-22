package com.xa.mass.transport.polling.delivery;

public enum PollingPendingDeliveryPollStatus {
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
     * The store is currently unavailable for poll work.
     */
    UNAVAILABLE,
    /**
     * The store has been shut down and should be treated as unavailable.
     */
    SHUTDOWN
}
