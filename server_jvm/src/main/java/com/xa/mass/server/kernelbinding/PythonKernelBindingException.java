package com.xa.mass.server.kernelbinding;

import org.springframework.http.HttpStatus;

public final class PythonKernelBindingException extends RuntimeException {

    private final HttpStatus responseStatus;
    private final String errorCode;

    private PythonKernelBindingException(
            HttpStatus responseStatus,
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.responseStatus = responseStatus;
        this.errorCode = errorCode;
    }

    public static PythonKernelBindingException unavailable(Throwable cause) {
        return new PythonKernelBindingException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "KERNEL_UNAVAILABLE",
                "Kernel control process is unavailable",
                cause
        );
    }

    public static PythonKernelBindingException timeout(Throwable cause) {
        return new PythonKernelBindingException(
                HttpStatus.GATEWAY_TIMEOUT,
                "KERNEL_TIMEOUT",
                "Kernel control request timed out",
                cause
        );
    }

    public static PythonKernelBindingException invalidResponse(
            String reason
    ) {
        return invalidResponse(reason, null);
    }

    public static PythonKernelBindingException invalidResponse(
            String reason,
            Throwable cause
    ) {
        return new PythonKernelBindingException(
                HttpStatus.BAD_GATEWAY,
                "INVALID_KERNEL_RESPONSE",
                reason,
                cause
        );
    }

    public static PythonKernelBindingException rejected(
            HttpStatus status,
            String reason
    ) {
        return new PythonKernelBindingException(
                status,
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
