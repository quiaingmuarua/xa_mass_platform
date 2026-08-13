package com.xa.mass.workerdelivery.adapter.netty.internal.process;

/** Adapter shutdown cutpoint at which a scheduled process stops. */
public enum QuiescePhase {
    BEFORE_NETWORK_CLOSE,
    AFTER_NETWORK_CLOSE
}
