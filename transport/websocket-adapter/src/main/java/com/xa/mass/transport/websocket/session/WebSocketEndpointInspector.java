package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket endpoint diagnostics projection.
 */
public final class WebSocketEndpointInspector implements WorkerEndpointInspector {

    private final WebSocketSessionStore sessionStore;

    public WebSocketEndpointInspector(WebSocketSessionStore sessionStore) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        return sessionStore.endpointSnapshots();
    }
}
