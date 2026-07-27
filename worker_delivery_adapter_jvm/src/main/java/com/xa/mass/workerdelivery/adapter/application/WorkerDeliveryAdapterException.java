package com.xa.mass.workerdelivery.adapter.application;

public final class WorkerDeliveryAdapterException
        extends RuntimeException {

    public WorkerDeliveryAdapterException(String message) {
        super(message);
    }

    public WorkerDeliveryAdapterException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
