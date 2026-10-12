package com.xa.mass.scenario.appchecks;

final class AppCheckConfigurationException extends RuntimeException {
    enum Code { INVALID_CONFIGURATION, CONFIGURATION_CONFLICT, STORAGE_UNAVAILABLE }
    final int status;
    final Code code;
    AppCheckConfigurationException(int status, Code code, String operation, String message, Throwable cause) {
        super("errorCode=" + code + " operation=appCheckConfiguration." + operation + " " + message, cause);
        this.status = status;
        this.code = code;
    }
    static AppCheckConfigurationException unavailable(String operation, Throwable cause) {
        return new AppCheckConfigurationException(503, Code.STORAGE_UNAVAILABLE, operation,
                "配置存储不可用或保存未确认，请重新读取核对；当前进程生效配置保持不变", cause);
    }
}
