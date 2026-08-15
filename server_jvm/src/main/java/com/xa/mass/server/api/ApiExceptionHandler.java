package com.xa.mass.server.api;

import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(ServerException.class)
    public ResponseEntity<ApiErrorResponse> serverFailure(
            ServerException error,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(statusFor(error.errorCode())).body(
                new ApiErrorResponse(
                        error.errorCode().code(),
                        error.getMessage(),
                        requestId(request)
                )
        );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiErrorResponse> malformedRequest(
            Exception error,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ApiErrorResponse(
                        ServerErrorCode.MALFORMED_REQUEST.code(),
                        ServerErrorCode.MALFORMED_REQUEST.defaultMessage(),
                        requestId(request)
                )
        );
    }

    private static HttpStatus statusFor(ServerErrorCode errorCode) {
        return switch (errorCode) {
            case KERNEL_UNAVAILABLE,
                    KERNEL_REJECTED_RETRYABLE,
                    TASK_DATA_UNAVAILABLE,
                    WORKER_DELIVERY_UNAVAILABLE,
                    WORKER_IDENTITY_UNAVAILABLE,
                    WORKER_BINDING_UNAVAILABLE,
                    WORKER_ENDPOINT_UNAVAILABLE,
                    RUNTIME_VIEW_UNAVAILABLE,
                    TASK_BATCH_UNAVAILABLE,
                    CONTROL_CALL_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case KERNEL_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case TASK_RPC_CAPACITY_EXCEEDED,
                    CONTROL_CALL_CAPACITY_EXCEEDED ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_KERNEL_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case KERNEL_REJECTED_NOT_FOUND,
                    TASK_NOT_FOUND,
                    WORKER_IDENTITY_NOT_FOUND,
                    WORKER_BINDING_NOT_FOUND,
                    WORKER_GROUP_NOT_FOUND,
                    TASK_BATCH_RESOURCE_NOT_FOUND,
                    CONTROL_CALL_TARGET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case KERNEL_REJECTED_CONFLICT,
                    WORKER_IDENTITY_CONFLICT,
                    WORKER_BINDING_CONFLICT,
                    TASK_BATCH_CONFLICT -> HttpStatus.CONFLICT;
            case KERNEL_REJECTED_INVALID,
                    RUNTIME_VIEW_FILTER_NOT_AVAILABLE ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_TASK_DATA_REQUEST,
                    INVALID_WORKER_DELIVERY_REQUEST,
                    INVALID_WORKER_IDENTITY_REQUEST,
                    INVALID_WORKER_BINDING_REQUEST,
                    TASK_BATCH_INVALID_REQUEST,
                    INVALID_CONTROL_CALL_REQUEST,
                    MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value instanceof String requestId ? requestId : null;
    }
}
