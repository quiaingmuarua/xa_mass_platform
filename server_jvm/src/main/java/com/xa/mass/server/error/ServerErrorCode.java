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
            "WorkerGroup RPC waiter capacity is exhausted"
    ),
    INVALID_WORKER_DELIVERY_REQUEST(
            13001,
            "Worker Delivery request is invalid"
    ),
    WORKER_DELIVERY_UNAVAILABLE(
            13002,
            "Worker Delivery Redis is unavailable"
    ),
    INVALID_WORKER_IDENTITY_REQUEST(
            14001,
            "Worker identity request is invalid"
    ),
    WORKER_IDENTITY_NOT_FOUND(
            14003,
            "Worker identity was not found"
    ),
    WORKER_IDENTITY_CONFLICT(
            14004,
            "Worker identity conflicts with current state"
    ),
    WORKER_IDENTITY_UNAVAILABLE(
            14005,
            "Worker identity service is unavailable"
    ),
    INVALID_WORKER_BINDING_REQUEST(
            14101,
            "Worker binding request is invalid"
    ),
    WORKER_BINDING_NOT_FOUND(
            14102,
            "Worker binding was not found"
    ),
    WORKER_BINDING_CONFLICT(
            14103,
            "Worker binding conflicts with current endpoint"
    ),
    WORKER_BINDING_UNAVAILABLE(
            14104,
            "Worker binding service is unavailable"
    ),
    WORKER_ENDPOINT_UNAVAILABLE(
            14105,
            "Worker endpoint is unavailable"
    ),
    WORKER_GROUP_NOT_FOUND(
            15001,
            "WorkerGroup was not found"
    ),
    RUNTIME_VIEW_UNAVAILABLE(
            15002,
            "Runtime View is unavailable"
    ),
    RUNTIME_VIEW_FILTER_NOT_AVAILABLE(
            15003,
            "Runtime View filter is not available"
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
