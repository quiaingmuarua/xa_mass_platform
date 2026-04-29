package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.model.WorkerTransportMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Fixed outbound sender for the current WebSocket adapter.
 */
public final class WebSocketOutputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketOutputProcessor.class);

    private final WebSocketDispatchRuntimeContext context;

    public WebSocketOutputProcessor(WebSocketDispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(WorkerTransportMessage delivery) {
        try {
            boolean sent = context.getEndpointRegistry().sendToRoute(delivery.getRouteKey(), delivery.getRawJson());
            if (sent) {
                return true;
            }
            logger.warn("WebSocket outbound skipped because endpoint is unavailable: routeKey={}, traceId={}",
                    delivery.getRouteKey(), delivery.getTraceId());
            return false;
        } catch (Exception ex) {
            logger.error("WebSocket outbound processing failed", ex);
            return false;
        }
    }
}
