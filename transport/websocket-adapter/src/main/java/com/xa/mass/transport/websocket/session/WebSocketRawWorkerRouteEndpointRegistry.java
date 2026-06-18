package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;

import java.util.Objects;

/**
 * WebSocket raw/manual route side-channel.
 *
 * <p>This is separate from assigned-delivery selected-worker dispatch. It is
 * retained only for raw/manual callers that still send by route.
 */
public final class WebSocketRawWorkerRouteEndpointRegistry implements RawWorkerRouteEndpointRegistry {

    private final String adapterId;
    private final WebSocketSessionController sessionController;

    public WebSocketRawWorkerRouteEndpointRegistry(String adapterId,
                                                   WebSocketSessionController sessionController) {
        this.adapterId = requireAdapterId(adapterId);
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sessionController.sendTextToEndpointAddress(routeKey, message);
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sessionController.hasEndpointAddress(routeKey);
    }

    private boolean matchesAdapter(String adapterId) {
        return adapterId == null || this.adapterId.equalsIgnoreCase(adapterId.trim());
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must not be blank");
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
