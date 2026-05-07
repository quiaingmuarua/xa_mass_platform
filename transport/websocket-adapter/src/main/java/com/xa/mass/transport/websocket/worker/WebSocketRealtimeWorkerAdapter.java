package com.xa.mass.transport.websocket.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.RuntimeDispatchOutcomes;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket-backed realtime worker adapter.
 *
 * <p>This adapter exposes concrete adapter identity {@code websocket} while
 * still belonging to the coarse {@code realtime} transport family.
 */
public final class WebSocketRealtimeWorkerAdapter implements WorkerAdapter {

    public static final String DEFAULT_ADAPTER_ID = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketRealtimeWorkerAdapter.class);

    private final String adapterId;
    private final TaskDispatchChannel taskDispatchChannel;

    public WebSocketRealtimeWorkerAdapter(String adapterId, TaskDispatchChannel taskDispatchChannel) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        this.adapterId = adapterId.trim();
        this.taskDispatchChannel = taskDispatchChannel;
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
    public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        if (taskDispatchChannel == null) {
            logger.warn("Skip task dispatch because WebSocket dispatch channel is unavailable: adapterId={}", adapterId);
            return RuntimeDispatchOutcomes.adapterUnavailable(adapterId, envelopes, "WebSocket dispatch channel is unavailable");
        }
        return taskDispatchChannel.dispatchEnvelopes(envelopes);
    }
}
