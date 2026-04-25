package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Fixed outbound sender for the current WebSocket adapter.
 */
public final class WebSocketOutputProcessor {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketOutputProcessor.class);

    private final DispatchRuntimeContext context;

    public WebSocketOutputProcessor(DispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public boolean process(OutboundDelivery delivery) {
        try {
            boolean sent = context.getEndpointRegistry().sendMessage(delivery.getWorkerId(), delivery.getRawJson());
            if (sent) {
                return true;
            }
            logger.warn("WebSocket outbound skipped because endpoint is unavailable: workerId={}, traceId={}",
                    delivery.getWorkerId(), delivery.getTraceId());
            return false;
        } catch (Exception ex) {
            logger.error("WebSocket outbound processing failed", ex);
            return false;
        }
    }
}
