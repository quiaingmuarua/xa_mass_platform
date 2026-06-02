package com.xa.mass.client.payload;

public class MassPayloadException extends RuntimeException {
    public MassPayloadException(String message) {
        super(message);
    }

    public MassPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
