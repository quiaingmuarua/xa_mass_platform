package com.xa.mass.starter;

import com.xa.mass.transport.channel.TransportResultIngressOutcome;

/**
 * Starter-owned result callback handling outcome.
 */
public enum ResultIngressHandleOutcome {
    HANDLED_APPLIED(true),
    HANDLED_NOOP(true),
    PERMANENT_REJECT(true),
    RETRYABLE_FAILURE(false);

    private final boolean ackable;

    ResultIngressHandleOutcome(boolean ackable) {
        this.ackable = ackable;
    }

    public boolean ackable() {
        return ackable;
    }

    public TransportResultIngressOutcome toTransportOutcome() {
        return ackable ? TransportResultIngressOutcome.ACKNOWLEDGED : TransportResultIngressOutcome.RETRYABLE_FAILURE;
    }
}
