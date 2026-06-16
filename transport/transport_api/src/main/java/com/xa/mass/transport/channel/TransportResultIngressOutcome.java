package com.xa.mass.transport.channel;

/**
 * Transport-visible inbox decision for a handled result ingress item.
 *
 * <p>This is not a task-result lifecycle outcome. It only tells transport
 * whether a claimed inbox item may be acknowledged or should remain retryable.</p>
 */
public enum TransportResultIngressOutcome {
    ACKNOWLEDGED(true),
    RETRYABLE_FAILURE(false);

    private final boolean ackable;

    TransportResultIngressOutcome(boolean ackable) {
        this.ackable = ackable;
    }

    public boolean ackable() {
        return ackable;
    }
}
