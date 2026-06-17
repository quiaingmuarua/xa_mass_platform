package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket endpoint diagnostics projection.
 */
public final class WebSocketEndpointInspector implements WorkerEndpointInspector {

    private final ServerSessionManager sessionManager;

    public WebSocketEndpointInspector(ServerSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        return sessionManager.listEndpointSnapshots();
    }
}
