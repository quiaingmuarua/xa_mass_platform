package com.xa.mass.server.api.v1.model;

public record ApiErrorResponse(
        String code,
        String message,
        String requestId
) {
}
