package com.xa.mass.client.http.exception;

import java.time.Duration;

public class MassTimeoutException extends MassClientException {
    private final String method;
    private final String path;
    private final Duration timeout;

    public MassTimeoutException(String method, String path, Duration timeout, Throwable cause) {
        super("Timed out after " + timeout.toMillis() + " ms calling " + method + " " + path, cause);
        this.method = method;
        this.path = path;
        this.timeout = timeout;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public Duration timeout() {
        return timeout;
    }
}
