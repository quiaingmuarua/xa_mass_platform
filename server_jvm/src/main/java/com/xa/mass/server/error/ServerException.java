package com.xa.mass.server.error;

import com.xa.mass.foundation.error.CodedRuntimeException;

public final class ServerException extends CodedRuntimeException {

    public ServerException(
            ServerErrorCode errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        super(errorCode, operation, message, cause);
    }

    @Override
    public ServerErrorCode errorCode() {
        return (ServerErrorCode) super.errorCode();
    }
}
