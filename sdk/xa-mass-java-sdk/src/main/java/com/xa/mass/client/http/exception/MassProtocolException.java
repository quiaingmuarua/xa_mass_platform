package com.xa.mass.client.http.exception;

public class MassProtocolException extends MassClientException {
    public MassProtocolException(String message) {
        super(message);
    }

    public MassProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
