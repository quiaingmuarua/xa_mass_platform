package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import com.xa.mass.transport.socket.worker.SocketRealtimeWorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch bridge for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final SocketSessionManager sessionManager;
    private final SocketTransportFrameCodec frameCodec;
    private final TransportDeliveryService deliveryService;

    public SocketTaskDispatchChannel(SocketSessionManager sessionManager,
                                     SocketTransportFrameCodec frameCodec,
                                     TransportDeliveryService deliveryService) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        return deliveryService.sendDirect(
                SocketRealtimeWorkerAdapter.PROTOCOL,
                envelopes,
                envelope -> {
                    String rawJson = frameCodec.encodeCanonicalTaskDispatch(envelope.getPayload());
                    boolean sent = sessionManager.sendMessage(envelope.getRouteKey(), rawJson);
                    if (!sent) {
                        logger.warn("Socket outbound skipped because endpoint is unavailable: routeKey={}, correlationKey={}",
                                envelope.getRouteKey(), envelope.getCorrelationKey());
                    }
                    return sent;
                },
                "socket dispatch channel is unavailable"
        );
    }
}
