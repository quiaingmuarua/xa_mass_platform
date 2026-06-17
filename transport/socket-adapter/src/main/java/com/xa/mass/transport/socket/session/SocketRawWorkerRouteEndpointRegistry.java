package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.RawWorkerRouteEndpointRegistry;

import java.util.Objects;

/**
 * Socket raw/manual route side-channel.
 */
public final class SocketRawWorkerRouteEndpointRegistry implements RawWorkerRouteEndpointRegistry {

    private final String adapterId;
    private final SocketSessionManager sessionManager;

    public SocketRawWorkerRouteEndpointRegistry(String adapterId,
                                                SocketSessionManager sessionManager) {
        this.adapterId = requireAdapterId(adapterId);
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
    }

    @Override
    public boolean sendToAdapterRoute(String adapterId, String routeKey, String message) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sessionManager.sendToRoute(routeKey, message);
    }

    @Override
    public boolean isAdapterRouteOnline(String adapterId, String routeKey) {
        if (!matchesAdapter(adapterId)) {
            return false;
        }
        return sessionManager.isAdapterRouteOnline(this.adapterId, routeKey);
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
