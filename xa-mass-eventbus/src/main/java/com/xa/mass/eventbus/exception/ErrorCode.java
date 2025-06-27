package com.xa.mass.eventbus.exception;

public enum ErrorCode {
    PARSE_ERROR(40001, "参数解析失败"),
    UNKNOWN_EVENT(40400, "未知事件类型"),
    UNKNOWN_ERROR(50000, "系统内部异常"),
    INIT_ERROR(50001, "初始化失败"),
    NETWORK_ERROR(50002, "网络异常"),
    TIMEOUT_ERROR(50003, "网络超时");

    public final int code;
    public final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
} 