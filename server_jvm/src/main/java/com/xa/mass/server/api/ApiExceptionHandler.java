package com.xa.mass.server.api;

import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.kernelclient.KernelClientException;
import com.xa.mass.server.taskdata.TaskDataException;
import com.xa.mass.server.workerdelivery.WorkerDeliveryException;
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

    @ExceptionHandler(KernelClientException.class)
    public ResponseEntity<ApiErrorResponse> kernelClientFailure(
            KernelClientException error,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(error.responseStatus()).body(
                new ApiErrorResponse(
                        error.errorCode(),
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
                        "MALFORMED_REQUEST",
                        "Request body or parameters are invalid",
                        requestId(request)
                )
        );
    }

    @ExceptionHandler(WorkerDeliveryException.class)
    public ResponseEntity<ApiErrorResponse> workerDeliveryFailure(
            WorkerDeliveryException error,
            HttpServletRequest request
    ) {
        HttpStatus status = error.kind()
                == WorkerDeliveryException.Kind.INVALID
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.SERVICE_UNAVAILABLE;
        String code = error.kind()
                == WorkerDeliveryException.Kind.INVALID
                ? "INVALID_WORKER_DELIVERY_REQUEST"
                : "WORKER_DELIVERY_UNAVAILABLE";
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        code,
                        error.getMessage(),
                        requestId(request)
                )
        );
    }

    @ExceptionHandler(TaskDataException.class)
    public ResponseEntity<ApiErrorResponse> taskDataFailure(
            TaskDataException error,
            HttpServletRequest request
    ) {
        HttpStatus status = switch (error.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = switch (error.kind()) {
            case INVALID -> "INVALID_TASK_DATA_REQUEST";
            case NOT_FOUND -> "TASK_NOT_FOUND";
            case UNAVAILABLE -> "TASK_DATA_UNAVAILABLE";
        };
        return ResponseEntity.status(status).body(
                new ApiErrorResponse(
                        code,
                        error.getMessage(),
                        requestId(request)
                )
        );
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value instanceof String requestId ? requestId : null;
    }
}
