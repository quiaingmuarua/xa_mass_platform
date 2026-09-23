package com.xa.mass.worker.error;

public enum WorkerErrorCode {
    COMMAND_POLL_FAILED(
            3101,
            "Worker command poll failed"
    ),
    RESULT_SUBMIT_FAILED(
            3103,
            "Worker result submission failed"
    ),
    WORKER_CONTROL_UNAVAILABLE(
            3104,
            "Worker control service is unavailable"
    ),
    WORKER_CONTROL_REJECTED(
            3105,
            "Worker control request was rejected"
    ),
    WORKER_CONTROL_RESPONSE_INVALID(
            3106,
            "Worker control response is invalid"
    ),
    COMMAND_MESSAGE_INVALID(
            3203,
            "Worker received an invalid command message"
    ),
    EVENT_INPUT_INVALID(
            3301,
            "Worker event input is invalid"
    ),
    EVENT_NOT_FOUND(
            3302,
            "Worker event definition was not found"
    ),
    EVENT_EXECUTION_FAILED(
            3303,
            "Worker event execution failed"
    ),
    EVENT_RESULT_INVALID(
            3304,
            "Worker event result is invalid"
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
