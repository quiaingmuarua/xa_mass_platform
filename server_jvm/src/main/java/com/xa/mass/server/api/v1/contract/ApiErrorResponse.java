package com.xa.mass.server.api.v1.contract;

public record ApiErrorResponse(
        int code,
        String message,
        String requestId
) {
}
