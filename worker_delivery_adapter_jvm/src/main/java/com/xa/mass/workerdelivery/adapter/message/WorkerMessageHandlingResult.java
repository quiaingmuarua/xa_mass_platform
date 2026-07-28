package com.xa.mass.workerdelivery.adapter.message;

public enum WorkerMessageHandlingResult {
    ACCEPTED,
    UNSUPPORTED_MESSAGE,
    INVALID_OUTCOME,
    BUFFER_FULL,
    ADAPTER_CLOSED
}
