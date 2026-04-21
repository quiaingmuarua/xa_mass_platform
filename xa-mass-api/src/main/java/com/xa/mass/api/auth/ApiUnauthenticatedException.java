package com.xa.mass.api.auth;

public class ApiUnauthenticatedException extends RuntimeException {
    public ApiUnauthenticatedException(String message) {
        super(message);
    }
}
