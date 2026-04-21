package com.xa.mass.mock.command.model;

public enum ErrorCode {
    PARSE_ERROR(40001, "request parse error"),
    UNKNOWN_EVENT(40400, "unknown event"),
    UNKNOWN_ERROR(50000, "unexpected command runtime error"),
    INIT_ERROR(50001, "command runtime init error"),
    NETWORK_ERROR(50002, "network error"),
    TIMEOUT_ERROR(50003, "network timeout"),
    XP_ENV_ERROR(50004, "xposed cache error"),
    TOKEN_IS_NULL(50005, "tachyonRegistrationToken is null");

    public final int code;
    public final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
