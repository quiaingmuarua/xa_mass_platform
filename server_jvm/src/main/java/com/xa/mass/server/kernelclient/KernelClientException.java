package com.xa.mass.server.kernelclient;

import org.springframework.http.HttpStatus;

public final class KernelClientException extends RuntimeException {

    private final HttpStatus responseStatus;
    private final String errorCode;

    private KernelClientException(
            HttpStatus responseStatus,
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.responseStatus = responseStatus;
        this.errorCode = errorCode;
    }

    public static KernelClientException unavailable(Throwable cause) {
        return new KernelClientException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "KERNEL_UNAVAILABLE",
                "Kernel command process is unavailable",
                cause
        );
    }

    public static KernelClientException timeout(Throwable cause) {
        return new KernelClientException(
                HttpStatus.GATEWAY_TIMEOUT,
                "KERNEL_TIMEOUT",
                "Kernel command request timed out",
                cause
        );
    }

    public static KernelClientException invalidResponse(String reason) {
        return invalidResponse(reason, null);
    }

    public static KernelClientException invalidResponse(
            String reason,
            Throwable cause
    ) {
        return new KernelClientException(
                HttpStatus.BAD_GATEWAY,
                "INVALID_KERNEL_RESPONSE",
                reason,
                cause
        );
    }

    public static KernelClientException rejected(
            HttpStatus responseStatus,
            String reason
    ) {
        return new KernelClientException(
                responseStatus,
                "KERNEL_REJECTED",
                reason,
                null
        );
    }

    public HttpStatus responseStatus() {
        return responseStatus;
    }

    public String errorCode() {
        return errorCode;
    }
}
