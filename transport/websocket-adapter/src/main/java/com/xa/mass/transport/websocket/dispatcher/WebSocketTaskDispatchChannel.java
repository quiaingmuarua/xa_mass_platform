package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutors;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.websocket.session.WebSocketSessionRegistry;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch channel.
 */
public final class WebSocketTaskDispatchChannel implements AdapterCommandExecutor {

    private final WebSocketSessionRegistry sessionRegistry;
    private final WorkerChannelFrameJsonCodec frameCodec;
    private final AdapterCommandExecutor delegate;

    public WebSocketTaskDispatchChannel(WebSocketSessionRegistry sessionRegistry) {
        this(sessionRegistry, new WorkerChannelFrameJsonCodec());
    }

    WebSocketTaskDispatchChannel(WebSocketSessionRegistry sessionRegistry,
                                 WorkerChannelFrameJsonCodec frameCodec) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.delegate = AdapterCommandExecutors.perMessage("WebSocket", this::send);
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        return delegate.dispatch(items);
    }

    private boolean send(DispatchMessage item) {
        return sessionRegistry.sendTextToWorker(
                item.selectedWorkerId(),
                frameCodec.encodeAction(item.payload()));
    }
}
