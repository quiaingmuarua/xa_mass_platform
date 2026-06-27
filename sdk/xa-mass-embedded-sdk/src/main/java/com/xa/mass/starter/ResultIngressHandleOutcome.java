package com.xa.mass.starter;

/**
 * Starter-owned result callback handling outcome.
 */
public enum ResultIngressHandleOutcome {
    HANDLED_APPLIED,
    HANDLED_NOOP,
    PERMANENT_REJECT,
    TRANSIENT_FAILURE
}
