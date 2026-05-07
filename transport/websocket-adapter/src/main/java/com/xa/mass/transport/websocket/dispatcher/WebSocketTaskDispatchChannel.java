package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch channel.
 */
public final class WebSocketTaskDispatchChannel implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTaskDispatchChannel.class);

    private final WebSocketDispatchRuntimeContext context;
    private final TransportDeliveryService deliveryService;

    public WebSocketTaskDispatchChannel(WebSocketDispatchRuntimeContext context,
                                        TransportDeliveryService deliveryService) {
        this.context = context;
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        if (context == null || context.getEndpointRegistry() == null || context.getFrameCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or endpoint registry is unavailable");
            return deliveryService.sendDirect(
                    adapterId(),
                    envelopes,
                    null,
                    "dispatcher context or endpoint registry is unavailable"
            );
        }
        return deliveryService.sendDirect(
                adapterId(),
                envelopes,
                envelope -> {
                    String rawJson = context.getFrameCodec().encodeCanonicalTaskDispatch(envelope.getPacket());
                    boolean sent = context.getEndpointRegistry().sendToAdapterRoute(
                            adapterId(),
                            envelope.getRouteKey(),
                            rawJson
                    );
                    if (!sent) {
                        logger.warn("WebSocket outbound skipped because endpoint is unavailable: routeKey={}, traceId={}",
                                envelope.getRouteKey(),
                                envelope.getCorrelationKey());
                    }
                    return sent;
                },
                "dispatcher context or endpoint registry is unavailable"
        );
    }

    private String adapterId() {
        if (context == null) {
            return WebSocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID;
        }
        String adapterId = context.getAdapterId();
        if (adapterId == null || adapterId.isBlank()) {
            return WebSocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID;
        }
        return adapterId;
    }
}
