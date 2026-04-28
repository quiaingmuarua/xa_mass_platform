package com.xa.mass.base.exception;

public enum ErrorCode {
    PARSE_ERROR(40001, "Parameter parsing failed"),
    UNKNOWN_EVENT(40400, "Unknown event type"),
    UNKNOWN_ERROR(50000, "Internal system error"),
    INIT_ERROR(50001, "Initialization failed"),
    NETWORK_ERROR(50002, "Network error"),
    TIMEOUT_ERROR(50003, "Network timeout");

    public final int code;
    public final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
