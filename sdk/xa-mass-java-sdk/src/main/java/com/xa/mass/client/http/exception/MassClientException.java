package com.xa.mass.client.http.exception;

public class MassClientException extends RuntimeException {
    public MassClientException(String message) {
        super(message);
    }

    public MassClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
