package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointRegistry;

import java.util.Objects;

public final class WebSocketSelectedWorkerRegistry implements WorkerEndpointRegistry {

    private final String adapterId;
    private final WebSocketSessionStore store;
    private final WebSocketSelectedWorkerSender sender;
    private final WebSocketSessionController sessionController;

    public WebSocketSelectedWorkerRegistry(String adapterId,
                                           WebSocketSessionStore store,
                                           WebSocketSelectedWorkerSender sender,
                                           WebSocketSessionController sessionController) {
        this.adapterId = requireAdapterId(adapterId);
        this.store = Objects.requireNonNull(store, "store");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
    }

    @Override
    public boolean sendToSelectedWorker(String adapterId, String selectedWorkerId, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
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

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
