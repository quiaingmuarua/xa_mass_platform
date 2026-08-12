package com.xa.mass.workerdelivery.adapter.netty.internal.connection;

public enum ConnectionCloseReason {
    ADAPTER_STOPPING("Adapter is stopping"),
    BINARY_UNSUPPORTED("Binary frames are unsupported"),
    INVALID_REPORT("Invalid Worker result"),
    IDENTITY_REQUIRED("Worker must identify first"),
    VERIFICATION_IN_PROGRESS("Worker route verification is in progress"),
    VERIFICATION_FAILED("Worker route verification failed"),
    REPLACED("Replaced by a newer Worker connection"),
    RESULT_BUFFER_FULL("Worker result buffer is full"),
    TRANSPORT_ERROR("Worker transport failed");

    private final String message;

    ConnectionCloseReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
