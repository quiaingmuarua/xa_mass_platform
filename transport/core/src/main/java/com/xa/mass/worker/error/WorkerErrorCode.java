package com.xa.mass.worker.error;

import com.xa.mass.foundation.error.ErrorCode;

public enum WorkerErrorCode implements ErrorCode {
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

    @Override
    public int code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
