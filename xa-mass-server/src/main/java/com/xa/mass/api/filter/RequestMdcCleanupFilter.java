package com.xa.mass.api.filter;

import com.xa.mass.api.observability.ServerApiFailureAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Resets MDC around each HTTP request so task/runtime context from earlier work
 * does not leak into unrelated request or shutdown logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestMdcCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MDC.clear();
        MDC.put("httpMethod", request.getMethod());
        MDC.put("httpPath", request.getRequestURI());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        request.setAttribute(ServerApiFailureAttributes.TRACE_ID_ATTR, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
