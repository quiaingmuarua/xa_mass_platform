package com.xa.mass.workerdelivery.adapter.internal;

enum ConnectionCloseReason {
    ADAPTER_STOPPING(1001, "Adapter is stopping"),
    BINARY_UNSUPPORTED(1003, "Binary frames are unsupported"),
    INVALID_REPORT(1007, "Invalid Worker result"),
    IDENTITY_REQUIRED(1008, "Worker must identify first"),
    VERIFICATION_IN_PROGRESS(
            1008,
            "Worker route verification is in progress"
    ),
    VERIFICATION_FAILED(1008, "Worker route verification failed"),
    REPLACED(1008, "Replaced by a newer Worker connection"),
    RESULT_BUFFER_FULL(1013, "Worker result buffer is full"),
    TRANSPORT_ERROR(1011, "Worker transport failed");

    private final int webSocketCode;
    private final String message;

    ConnectionCloseReason(int webSocketCode, String message) {
        this.webSocketCode = webSocketCode;
        this.message = message;
    }

    int webSocketCode() {
        return webSocketCode;
    }

    String message() {
        return message;
    }
}
