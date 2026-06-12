package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch channel.
 */
public final class WebSocketTaskDispatchChannel implements WorkerAdapter {

    public static final String DEFAULT_ADAPTER_ID = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTaskDispatchChannel.class);

    private final WebSocketDispatcherContext context;
    private final TransportDeliveryService deliveryService;

    public WebSocketTaskDispatchChannel(WebSocketDispatcherContext context,
                                        TransportDeliveryService deliveryService) {
        this.context = context;
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public String protocol() {
        return adapterId();
    }

    @Override
    public String adapterId() {
        return context != null ? context.getAdapterId() : DEFAULT_ADAPTER_ID;
    }

    @Override
    public String transportHint() {
        return WorkerTransportHints.REALTIME;
    }

    @Override
    public List<DispatchOutcome> dispatch(List<AdapterDispatchRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (context == null || context.getEndpointRegistry() == null || context.getFrameCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or endpoint registry is unavailable");
            return deliveryService.sendDirect(
                    adapterId(),
                    requests,
                    null,
                    "dispatcher context or endpoint registry is unavailable"
            );
        }
        return deliveryService.sendDirect(
                adapterId(),
                requests,
                request -> {
                    String rawJson = context.getFrameCodec().encodeCanonicalTaskDispatch(request);
                    boolean sent = context.getEndpointRegistry().sendToSelectedWorker(
                            adapterId(),
                            request.selectedWorkerId(),
                            rawJson
                    );
                    if (!sent) {
                        logger.warn("WebSocket outbound skipped because endpoint is unavailable: routeKey={}, traceId={}",
                                request.endpoint().routeKey(),
                                null);
                    }
                    return sent;
                },
                "dispatcher context or endpoint registry is unavailable"
        );
    }
}
