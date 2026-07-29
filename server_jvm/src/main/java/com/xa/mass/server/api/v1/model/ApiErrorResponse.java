package com.xa.mass.server.api.v1.model;

public record ApiErrorResponse(
        int code,
        String message,
        String requestId
) {
}
