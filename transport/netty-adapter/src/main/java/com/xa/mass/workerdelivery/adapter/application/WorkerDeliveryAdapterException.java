package com.xa.mass.workerdelivery.adapter.application;

import java.util.Objects;

public final class WorkerDeliveryAdapterException
        extends RuntimeException {

    private final WorkerDeliveryAdapterErrorCode errorCode;
    private final String operation;

    public WorkerDeliveryAdapterException(
            WorkerDeliveryAdapterErrorCode errorCode,
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

    public WorkerDeliveryAdapterErrorCode errorCode() {
        return errorCode;
    }

    public String operation() {
        return operation;
    }

    private static WorkerDeliveryAdapterErrorCode requireErrorCode(
            WorkerDeliveryAdapterErrorCode errorCode
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
