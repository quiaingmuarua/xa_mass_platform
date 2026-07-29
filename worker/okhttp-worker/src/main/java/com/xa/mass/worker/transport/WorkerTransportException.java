package com.xa.mass.worker.transport;

public final class WorkerTransportException extends RuntimeException {

    public WorkerTransportException(String message) {
        super(message);
    }

    public WorkerTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
