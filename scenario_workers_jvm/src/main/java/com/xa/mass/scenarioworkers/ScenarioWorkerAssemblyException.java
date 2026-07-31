package com.xa.mass.scenarioworkers;

import java.util.Objects;

final class ScenarioWorkerAssemblyException extends RuntimeException {

    private final int errorCode;
    private final String operation;

    ScenarioWorkerAssemblyException(
            int errorCode,
            String operation,
            String message
    ) {
        this(errorCode, operation, message, null);
    }

    ScenarioWorkerAssemblyException(
            int errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        super(format(errorCode, operation, message), cause);
        if (errorCode <= 0) {
            throw new IllegalArgumentException(
                    "errorCode must be positive"
            );
        }
        this.errorCode = errorCode;
        this.operation = requireOperation(operation);
    }

    int errorCode() {
        return errorCode;
    }

    String operation() {
        return operation;
    }

    private static String format(
            int errorCode,
            String operation,
            String message
    ) {
        return "["
                + errorCode
                + " "
                + requireOperation(operation)
                + "] "
                + Objects.requireNonNull(message, "message");
    }

    private static String requireOperation(String value) {
        Objects.requireNonNull(value, "operation");
        int separator = value.indexOf('.');
        if (value.isBlank()
                || separator <= 0
                || separator == value.length() - 1
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "operation must use owner.method form"
            );
        }
        return value;
    }
}
