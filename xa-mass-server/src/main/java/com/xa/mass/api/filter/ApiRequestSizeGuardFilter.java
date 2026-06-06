package com.xa.mass.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.observability.ServerApiFailureAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRequestSizeGuardFilter extends OncePerRequestFilter {

    private static final long MAX_TASK_SHELL_CREATE_BYTES =
            Long.getLong("xa.mass.api.maxTaskShellCreateBytes", 64L * 1024L);
    private static final long MAX_ITEM_INGEST_REQUEST_BYTES =
            Long.getLong("xa.mass.api.maxTaskItemIngestRequestBytes", 1024L * 1024L);
    private static final long MAX_INTERNAL_SYNC_REQUEST_BYTES =
            Long.getLong("xa.mass.api.maxInternalSyncRequestBytes", 1024L * 1024L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long maxAllowedBytes = maxAllowedBytes(request);
        if (maxAllowedBytes != null) {
            long contentLength = request.getContentLengthLong();
            if (contentLength > maxAllowedBytes) {
                ServerApiFailureAttributes.markFailure(
                        request,
                        ServerApiFailureAttributes.PAYLOAD_TOO_LARGE,
                        "Request body exceeds size limit"
                );
                writePayloadTooLarge(response, maxAllowedBytes, contentLength);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Long maxAllowedBytes(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if (!"POST".equalsIgnoreCase(method)) {
            return null;
        }
        if ("/internal/v1/debug/task-invocations:sync".equals(uri)) {
            return MAX_INTERNAL_SYNC_REQUEST_BYTES;
        }
        if ("/api/v1/tasks".equals(uri)) {
            return MAX_TASK_SHELL_CREATE_BYTES;
        }
        if (uri != null && uri.matches("^/api/v1/tasks/[^/]+/items$")) {
            return MAX_ITEM_INGEST_REQUEST_BYTES;
        }
        if (uri != null && uri.matches("^/api/v1/tasks/[^/]+/items:sync$")) {
            return MAX_ITEM_INGEST_REQUEST_BYTES;
        }
        return null;
    }

    private void writePayloadTooLarge(HttpServletResponse response,
                                      long maxAllowedBytes,
                                      long contentLength) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body exceeds size limit: " + contentLength + " > " + maxAllowedBytes
        ));
    }
}
