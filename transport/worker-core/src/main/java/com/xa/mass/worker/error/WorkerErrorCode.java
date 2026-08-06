package com.xa.mass.worker.error;

public enum WorkerErrorCode {
    COMMAND_POLL_FAILED(
            31001,
            "Worker command poll failed"
    ),
    RESULT_SUBMIT_FAILED(
            31003,
            "Worker result submission failed"
    ),
    WORKER_CONTROL_UNAVAILABLE(
            31004,
            "Worker control service is unavailable"
    ),
    WORKER_CONTROL_REJECTED(
            31005,
            "Worker control request was rejected"
    ),
    WORKER_CONTROL_RESPONSE_INVALID(
            31006,
            "Worker control response is invalid"
    ),
    COMMAND_MESSAGE_INVALID(
            32003,
            "Worker received an invalid command message"
    ),
    EVENT_INPUT_INVALID(
            33001,
            "Worker event input is invalid"
    ),
    EVENT_NOT_FOUND(
            33002,
            "Worker event is not registered"
    );

    private final int code;
    private final String defaultMessage;

    WorkerErrorCode(int code, String defaultMessage) {
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
