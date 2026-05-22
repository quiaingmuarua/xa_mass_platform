package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.TransportOutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Fixed outbound sender for the current WebSocket adapter.
 */
public final class WebSocketOutputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketOutputProcessor.class);

    private final WebSocketDispatcherContext context;

    public WebSocketOutputProcessor(WebSocketDispatcherContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(TransportOutboundMessage delivery) {
        try {
            boolean sent = context.getEndpointRegistry().sendToAdapterRoute(
                    context.getAdapterId(),
                    delivery.getRouteKey(),
                    delivery.getRawJson()
            );
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

