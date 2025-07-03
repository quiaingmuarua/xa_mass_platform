package com.xa.mass.base.jsondsl;

/**
 * mock 过程中的统一异常。
 */
public class JsonDslException extends RuntimeException {
    public JsonDslException(String message) {
        super(message);
    }

    public JsonDslException(String message, Throwable cause) {
        super(message, cause);
    }
} 