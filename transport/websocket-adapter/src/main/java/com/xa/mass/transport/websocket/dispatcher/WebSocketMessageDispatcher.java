package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deprecated compatibility wrapper retained for the legacy
 * {@code MassWebSocketAdapter} escape hatch.
 *
 * <p>The embedded-runtime mainline no longer runs queue-polling dispatcher
 * loops here. WebSocket transport now routes inbound and outbound frames
 * directly through adapter-owned server/session paths.
 */
@Deprecated(forRemoval = false)
public final class WebSocketMessageDispatcher {

    private final WebSocketInputProcessor inputProcessor;
    private final WebSocketOutputProcessor outputProcessor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WebSocketMessageDispatcher(WebSocketDispatchRuntimeContext context) {
        Objects.requireNonNull(context, "context");
        this.inputProcessor = new WebSocketInputProcessor(context);
        this.outputProcessor = new WebSocketOutputProcessor(context);
    }

    public void start() {
        running.set(true);
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean dispatchInboundRawMessage(String rawJson) {
        return inputProcessor.process(rawJson);
    }

    public boolean dispatchOutboundDelivery(WorkerTransportMessage delivery) {
        return outputProcessor.process(delivery);
    }
}
