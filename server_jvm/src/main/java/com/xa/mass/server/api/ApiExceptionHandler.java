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
                        error.errorCode().defaultMessage(),
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
            case TASK_DATA_UNAVAILABLE,
                    TASK_CALL_REGISTRATION_UNAVAILABLE,
                    WORKER_DELIVERY_UNAVAILABLE,
                    WORKER_IDENTITY_UNAVAILABLE,
                    WORKER_BINDING_UNAVAILABLE,
                    WORKER_ENDPOINT_UNAVAILABLE,
                    RUNTIME_VIEW_UNAVAILABLE,
                    WORKER_SCHEDULING_UNAVAILABLE,
                    WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    WORKER_RESOURCE_UNAVAILABLE,
                    DIRECT_CALL_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case DIRECT_CALL_CAPACITY_EXCEEDED ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case KERNEL_REJECTED_CONFLICT,
                    TASK_NOT_FOUND,
                    TASK_OPERATION_NOT_SUPPORTED,
                    TASK_STATE_CONFLICT,
                    TASK_RESULTS_NOT_READY,
                    TASK_WORKER_GROUP_NOT_FOUND,
                    INVALID_TASK_DATA_REQUEST,
                    TASK_CALL_NOT_REGISTERED,
                    TASK_CALL_REGISTRATION_CONFLICT,
                    INVALID_WORKER_DELIVERY_REQUEST,
                    INVALID_WORKER_IDENTITY_REQUEST,
                    WORKER_IDENTITY_NOT_FOUND,
                    WORKER_IDENTITY_CONFLICT,
                    INVALID_WORKER_BINDING_REQUEST,
                    WORKER_BINDING_NOT_FOUND,
                    WORKER_BINDING_CONFLICT,
                    WORKER_GROUP_NOT_FOUND,
                    RUNTIME_VIEW_FILTER_NOT_AVAILABLE,
                    INVALID_WORKER_GROUP_REQUEST,
                    WORKER_GROUP_REGISTRATION_CONFLICT,
                    WORKER_RESOURCE_NOT_FOUND,
                    WORKER_RESOURCE_STATE_CONFLICT,
                    INVALID_WORKER_RESOURCE_REQUEST,
                    INVALID_DIRECT_CALL_REQUEST,
                    DIRECT_CALL_TARGET_NOT_FOUND,
                    MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value instanceof String requestId ? requestId : null;
    }
}
