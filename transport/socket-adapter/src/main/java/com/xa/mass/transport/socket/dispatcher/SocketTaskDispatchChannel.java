package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch channel for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final SocketTransportFrameCodec frameCodec;
    private final SocketSessionManager sessionManager;

    public SocketTaskDispatchChannel(SocketTransportFrameCodec frameCodec,
                                     SocketSessionManager sessionManager) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (DispatchMessage item : items) {
            outcomes.add(dispatchOne(item));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private DispatchOutcome dispatchOne(DispatchMessage item) {
        if (item == null) {
            return DispatchOutcome.invalid(null, null, null, "request must not be null");
        }
        try {
            String rawJson = frameCodec.encodeCanonicalTaskDispatch(item);
            boolean sent = sessionManager.sendToWorker(item.selectedWorkerId(), rawJson);
            if (!sent) {
                logger.warn("Socket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                        item.selectedWorkerId(), null);
                return DispatchOutcomeFactory.noEndpoint(item, "endpoint is unavailable");
            }
            return DispatchOutcomeFactory.delivered(item);
        } catch (RuntimeException e) {
            logger.warn("Socket outbound failed: selectedWorkerId={}, reason={}",
                    item.selectedWorkerId(), e.getMessage());
            return DispatchOutcomeFactory.failed(item, e.getMessage(), true);
        }
    }
}
