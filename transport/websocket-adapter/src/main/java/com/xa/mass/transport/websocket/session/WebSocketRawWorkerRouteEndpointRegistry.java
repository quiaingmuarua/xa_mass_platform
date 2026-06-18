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
    private final WebSocketSessionStore sessionStore;

    public WebSocketRawWorkerRouteEndpointRegistry(String adapterId,
                                                   WebSocketSessionStore sessionStore) {
        this.adapterId = requireAdapterId(adapterId);
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        for (WebSocketSessionRecord record : sessionStore.activeRecordsForEndpointAddress(routeKey)) {
            record.send(message);
            return true;
        }
        return false;
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sessionStore.hasActiveEndpointAddress(routeKey);
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
