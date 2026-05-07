package com.xa.mass.transport.socket.worker;

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
 * Raw TCP socket-backed realtime worker adapter.
 */
public final class SocketRealtimeWorkerAdapter implements WorkerAdapter {

    public static final String DEFAULT_ADAPTER_ID = "socket";

    private static final Logger logger = LoggerFactory.getLogger(SocketRealtimeWorkerAdapter.class);

    private final String adapterId;
    private final TaskDispatchChannel taskDispatchChannel;

    public SocketRealtimeWorkerAdapter(String adapterId, TaskDispatchChannel taskDispatchChannel) {
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
            logger.warn("Skip task dispatch because socket dispatch channel is unavailable: adapterId={}", adapterId);
            return RuntimeDispatchOutcomes.adapterUnavailable(adapterId, envelopes, "socket dispatch channel is unavailable");
        }
        return taskDispatchChannel.dispatchEnvelopes(envelopes);
    }
}
