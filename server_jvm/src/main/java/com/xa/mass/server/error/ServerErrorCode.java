package com.xa.mass.server.error;

public enum ServerErrorCode {
    KERNEL_UNAVAILABLE(
            11001,
            "Kernel control process is unavailable"
    ),
    KERNEL_TIMEOUT(
            11002,
            "Kernel control request timed out"
    ),
    INVALID_KERNEL_RESPONSE(
            11003,
            "Kernel control response is invalid"
    ),
    KERNEL_REJECTED_NOT_FOUND(
            11004,
            "Kernel resource was not found"
    ),
    KERNEL_REJECTED_CONFLICT(
            11005,
            "Kernel control request conflicts with current state"
    ),
    KERNEL_REJECTED_INVALID(
            11006,
            "Kernel control request is invalid"
    ),
    KERNEL_REJECTED_RETRYABLE(
            11007,
            "Kernel control request should be retried"
    ),
    INVALID_TASK_DATA_REQUEST(
            12001,
            "Task data request is invalid"
    ),
    TASK_NOT_FOUND(
            12002,
            "Task was not found"
    ),
    TASK_DATA_UNAVAILABLE(
            12003,
            "Task data Redis is unavailable"
    ),
    TASK_RPC_CAPACITY_EXCEEDED(
            12004,
            "Task RPC waiter capacity is exhausted"
    ),
    INVALID_WORKER_DELIVERY_REQUEST(
            13001,
            "Worker Delivery request is invalid"
    ),
    WORKER_DELIVERY_UNAVAILABLE(
            13002,
            "Worker Delivery Redis is unavailable"
    ),
    MALFORMED_REQUEST(
            19001,
            "Request body or parameters are invalid"
    );

    private final int code;
    private final String defaultMessage;

    ServerErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
