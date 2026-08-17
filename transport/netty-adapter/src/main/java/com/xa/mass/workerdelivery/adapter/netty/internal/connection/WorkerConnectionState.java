package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

/**
 * Best-effort point-in-time projection of one Worker route inside an Adapter.
 *
 * <p>This is neither Worker lifecycle truth nor scheduling eligibility.
 */
public enum WorkerConnectionState {
    UNKNOWN,
    VERIFYING,
    CONNECTED,
    DISCONNECTED
}
