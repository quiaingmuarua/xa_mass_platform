package com.xa.mass.worker.error;

import com.xa.mass.foundation.error.CodedRuntimeException;

public final class WorkerException extends CodedRuntimeException {

    public WorkerException(
            WorkerErrorCode errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        super(errorCode, operation, message, cause);
    }

    @Override
    public WorkerErrorCode errorCode() {
        return (WorkerErrorCode) super.errorCode();
    }
}
