package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch channel for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements AdapterCommandExecutor {

    private final SocketTransportFrameCodec frameCodec;
    private final SocketSessionManager sessionManager;
    private final AdapterCommandExecutor delegate;

    public SocketTaskDispatchChannel(SocketTransportFrameCodec frameCodec,
                                     SocketSessionManager sessionManager) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.delegate = AdapterCommandExecutors.perMessage("Socket", this::send);
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        return delegate.dispatch(items);
    }

    private boolean send(DispatchMessage item) {
        String rawJson = frameCodec.encodeCanonicalTaskDispatch(item);
        return sessionManager.sendToWorker(item.selectedWorkerId(), rawJson);
    }
}
