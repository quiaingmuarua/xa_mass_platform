package com.xa.mass.server.workerdelivery.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriUtils;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.websocket",
        name = "enabled",
        havingValue = "true"
)
public class WorkerWebSocketConfiguration implements WebSocketConfigurer {

    public static final String WORKER_PATH =
            "/api/v1/worker-delivery/websocket/workers/*";
    private static final String WORKER_PATH_PREFIX =
            "/api/v1/worker-delivery/websocket/workers/";
    private final WorkerWebSocketHandler handler;

    public WorkerWebSocketConfiguration(WorkerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(handler, WORKER_PATH)
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
            if (!path.startsWith(WORKER_PATH_PREFIX)) {
                return false;
            }
            String workerId = UriUtils.decode(
                    path.substring(WORKER_PATH_PREFIX.length()),
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
