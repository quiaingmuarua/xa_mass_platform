package com.xa.mass.server.error;

import java.util.Objects;

public final class ServerException extends RuntimeException {

    private final ServerErrorCode errorCode;
    private final String operation;

    public ServerException(
            ServerErrorCode errorCode,
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

    public ServerErrorCode errorCode() {
        return errorCode;
    }

    public String operation() {
        return operation;
    }

    private static ServerErrorCode requireErrorCode(
            ServerErrorCode errorCode
    ) {
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
