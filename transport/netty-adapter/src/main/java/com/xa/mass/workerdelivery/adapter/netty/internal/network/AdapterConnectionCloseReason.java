package com.xa.mass.workerdelivery.adapter.netty.internal.network;

/** Semantic close causes interpreted by the selected physical protocol. */
public enum AdapterConnectionCloseReason {
    ADAPTER_STOPPING,
    BINARY_UNSUPPORTED,
    INVALID_REPORT,
    IDENTITY_REQUIRED,
    VERIFICATION_IN_PROGRESS,
    VERIFICATION_FAILED,
    REPLACED,
    MANAGEMENT_REQUEST,
    RESULT_BUFFER_FULL,
    TRANSPORT_ERROR
}
