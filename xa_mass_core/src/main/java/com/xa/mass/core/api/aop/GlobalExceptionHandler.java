package com.xa.mass.core.api.aop;

import com.xa.mass.core.api.model.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleException(Exception ex) {
        // 可根据需要打印日志
        return ApiResponse.error(500, ex.getMessage() != null ? ex.getMessage() : "Internal Server Error");
    }
} 