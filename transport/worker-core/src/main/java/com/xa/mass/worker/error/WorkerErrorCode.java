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
