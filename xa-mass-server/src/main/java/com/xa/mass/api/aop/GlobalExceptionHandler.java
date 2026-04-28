package com.xa.mass.api.aop;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.auth.ApiForbiddenException;
import com.xa.mass.api.auth.ApiUnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, ex.getMessage()));
    }

    @ExceptionHandler(ApiUnauthenticatedException.class)
    public ResponseEntity<ApiResponse<?>> handleUnauthenticated(ApiUnauthenticatedException ex) {
        return ResponseEntity.status(401).body(ApiResponse.error(401, ex.getMessage()));
    }

    @ExceptionHandler(ApiForbiddenException.class)
    public ResponseEntity<ApiResponse<?>> handleForbidden(ApiForbiddenException ex) {
        return ResponseEntity.status(403).body(ApiResponse.error(403, ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<?>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleBadJson(HttpMessageNotReadableException ex) {
        Throwable mostSpecificCause = ex.getMostSpecificCause();
        String msg = mostSpecificCause != null && mostSpecificCause.getMessage() != null
                ? mostSpecificCause.getMessage()
                : "Request body is invalid";
        return ResponseEntity.badRequest().body(ApiResponse.error(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception ex) {
        logger.error("Unhandled exception in API layer", ex);
        String msg = ex.getMessage() != null ? ex.getMessage() : "Internal Server Error";
        return ResponseEntity.status(500).body(ApiResponse.error(500, msg));
    }
}
