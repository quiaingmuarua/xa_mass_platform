package com.xa.mass.workerdelivery.adapter.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriUtils;

public final class WorkerWebSocketEndpointConfigurer
        implements WebSocketConfigurer {

    private final WorkerWebSocketHandler handler;

    public WorkerWebSocketEndpointConfigurer(
            WorkerWebSocketHandler handler
    ) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                        handler,
                        WorkerWebSocketHandler.WORKER_PATH
                )
                .addInterceptors(new WorkerIdentityInterceptor());
    }

    private static final class WorkerIdentityInterceptor
            implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes
        ) {
            String path = request.getURI().getRawPath();
            if (!path.startsWith(
                    WorkerWebSocketHandler.WORKER_PATH_PREFIX
            )) {
                return false;
            }
            String workerId = UriUtils.decode(
                    path.substring(
                            WorkerWebSocketHandler
                                    .WORKER_PATH_PREFIX.length()
                    ),
                    StandardCharsets.UTF_8
            );
            if (workerId.isBlank() || workerId.contains("/")) {
                return false;
            }
            attributes.put(
                    WorkerWebSocketHandler.WORKER_ID_ATTRIBUTE,
                    workerId
            );
            return true;
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception
        ) {
        }
    }
}
