package com.xa.mass.workerdelivery.adapter.netty.internal.network;

/** Physical result of attempting to write one normalized text value. */
public enum TextWriteAttempt {
    STARTED,
    RETRY_LATER,
    UNKNOWN
}
