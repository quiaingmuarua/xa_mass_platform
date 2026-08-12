package com.xa.mass.workerdelivery.adapter.application;

public enum WorkerDeliveryAdapterErrorCode {
    INVALID_CONFIGURATION(
            21001,
            "Worker Delivery Adapter configuration is invalid"
    ),
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
    GATEWAY_UNAVAILABLE(
            22001,
            "Worker Delivery Gateway is unavailable"
    ),
    GATEWAY_PROTOCOL_ERROR(
            22002,
            "Worker Delivery Gateway response is invalid"
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
