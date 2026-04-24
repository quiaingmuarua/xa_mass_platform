package com.xa.mass.gateway.model.enums;

/**
 * Wire-frame categories used by the current gateway adapter.
 *
 * <p>These values classify transport frames only. Runtime capability identity
 * belongs to global SDK event codes, while task execution semantics belong to
 * transport-neutral dispatch/result models.
 */
public enum MessageType {
    TASK,
    PING,
    PONG,
    CONTROL
}
