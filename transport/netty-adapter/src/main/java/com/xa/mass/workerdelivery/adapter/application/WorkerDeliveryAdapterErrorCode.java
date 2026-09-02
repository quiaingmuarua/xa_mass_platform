package com.xa.mass.workerdelivery.adapter.application;

public enum WorkerDeliveryAdapterErrorCode {
    LISTENER_START_FAILED(
            21002,
            "Worker Delivery Adapter listener could not start"
    ),
    SHUTDOWN_INTERRUPTED(
            21003,
            "Worker Delivery Adapter shutdown was interrupted"
    ),
    SHUTDOWN_TIMEOUT(
            21004,
            "Worker Delivery Adapter shutdown exceeded its owner budget"
    ),
    REMOTE_API_UNAVAILABLE(
            22001,
            "Worker Delivery remote API is unavailable"
    ),
    REMOTE_API_PROTOCOL_ERROR(
            22002,
            "Worker Delivery remote API response is invalid"
    ),
    WORKER_ROUTE_REJECTED(
            22003,
            "Worker route was rejected"
    ),
    DELIVERY_INTERRUPTED(
            23001,
            "Worker command delivery was interrupted"
    ),
    COMMAND_EXPIRED(
            23002,
            "Worker command expired before delivery"
    ),
    WORKER_MESSAGE_INVALID(
            23003,
            "Worker message is invalid"
    ),
    ADAPTER_COMMAND_INVALID(
            23004,
            "Adapter-targeted command is invalid"
    ),
    ADAPTER_EVENT_UNSUPPORTED(
            23005,
            "Adapter event is unsupported"
    ),
    ADAPTER_EVENT_EXECUTION_FAILED(
            23006,
            "Adapter event execution failed"
    ),
    WORKER_DELIVERY_RETRY_LATER(
            23007,
            "Worker command delivery should be retried later"
    );

    private final int code;
    private final String defaultMessage;

    WorkerDeliveryAdapterErrorCode(
            int code,
            String defaultMessage
    ) {
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
