package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch channel for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements WorkerAdapter {

    public static final String DEFAULT_ADAPTER_ID = "socket";

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final String adapterId;
    private final SocketSessionManager sessionManager;
    private final SocketTransportFrameCodec frameCodec;
    private final TransportDeliveryService deliveryService;

    public SocketTaskDispatchChannel(String adapterId,
                                     SocketSessionManager sessionManager,
                                     SocketTransportFrameCodec frameCodec,
                                     TransportDeliveryService deliveryService) {
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public String protocol() {
        return adapterId;
    }

    @Override
    public String adapterId() {
        return adapterId;
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
        return deliveryService.sendDirect(
                adapterId,
                requests,
                request -> {
                    String rawJson = frameCodec.encodeCanonicalTaskDispatch(request);
                    boolean sent = sessionManager.sendToSelectedWorker(
                            adapterId,
                            request.selectedWorkerId(),
                            rawJson
                    );
                    if (!sent) {
                        logger.warn("Socket outbound skipped because endpoint is unavailable: routeKey={}, traceId={}",
                                request.endpoint().routeKey(), null);
                    }
                    return sent;
                },
                "socket dispatch channel is unavailable"
        );
    }
}
