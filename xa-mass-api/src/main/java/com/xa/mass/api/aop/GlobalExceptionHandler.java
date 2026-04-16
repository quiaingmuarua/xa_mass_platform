package com.xa.mass.api.aop;

import com.xa.mass.api.model.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception ex) {
        // Add centralized logging here if the API surface needs it later.
        return ApiResponse.error(500, ex.getMessage() != null ? ex.getMessage() : "Internal Server Error");
    }
}
