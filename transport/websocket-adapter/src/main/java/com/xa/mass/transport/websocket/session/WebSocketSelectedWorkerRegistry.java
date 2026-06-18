package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.Objects;

public final class WebSocketSelectedWorkerRegistry implements WorkerEndpointRegistry {

    private final WebSocketSessionStore store;
    private final WebSocketSelectedWorkerSender sender;
    private final WebSocketSessionController sessionController;

    public WebSocketSelectedWorkerRegistry(WebSocketSessionStore store,
                                           WebSocketSelectedWorkerSender sender,
                                           WebSocketSessionController sessionController) {
        this.store = Objects.requireNonNull(store, "store");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
    }

    @Override
    public boolean sendToSelectedWorker(String selectedWorkerId, String message) {
        String normalizedSelectedWorkerId = normalizeNullable(selectedWorkerId);
        return normalizedSelectedWorkerId != null
                && sender.sendToSelectedWorker(normalizedSelectedWorkerId, message);
    }

    @Override
    public int getActiveConnectionCount() {
        return store.activeConnectionCount();
    }

    @Override
    public void shutdown() {
        sessionController.shutdown();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
