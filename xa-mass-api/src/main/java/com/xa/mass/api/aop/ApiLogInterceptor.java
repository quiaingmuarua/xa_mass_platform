package com.xa.mass.api.aop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class ApiLogInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ApiLogInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        log.info("[API] {} {} from {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        return true;
    }
} 