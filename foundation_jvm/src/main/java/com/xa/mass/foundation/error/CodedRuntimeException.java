package com.xa.mass.foundation.error;

import java.util.Objects;

public abstract class CodedRuntimeException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String operation;

    protected CodedRuntimeException(
            ErrorCode errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        super(
                message == null
                        ? requireErrorCode(errorCode).defaultMessage()
                        : message,
                cause
        );
        this.errorCode = requireErrorCode(errorCode);
        this.operation = requireOperation(operation);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String operation() {
        return operation;
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "errorCode");
    }

    private static String requireOperation(String operation) {
        Objects.requireNonNull(operation, "operation");
        int separator = operation.indexOf('.');
        if (operation.isBlank()
                || separator <= 0
                || separator == operation.length() - 1
                || operation.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "operation must use non-blank owner.method form"
            );
        }
        return operation;
    }
}
