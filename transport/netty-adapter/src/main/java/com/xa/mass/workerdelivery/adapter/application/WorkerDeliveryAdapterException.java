package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.foundation.error.CodedRuntimeException;

public final class WorkerDeliveryAdapterException
        extends CodedRuntimeException {

    public WorkerDeliveryAdapterException(
            WorkerDeliveryAdapterErrorCode errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        super(errorCode, operation, message, cause);
    }

    @Override
    public WorkerDeliveryAdapterErrorCode errorCode() {
        return (WorkerDeliveryAdapterErrorCode) super.errorCode();
    }
}
