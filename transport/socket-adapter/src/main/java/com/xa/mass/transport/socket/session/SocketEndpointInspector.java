package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Socket endpoint diagnostics projection.
 */
public final class SocketEndpointInspector implements WorkerEndpointInspector {

    private final SocketSessionManager sessionManager;

    public SocketEndpointInspector(SocketSessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
    }

    @Override
    public List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        return sessionManager.listEndpointSnapshots();
    }
}
