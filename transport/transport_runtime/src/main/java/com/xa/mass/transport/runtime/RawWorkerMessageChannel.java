package com.xa.mass.transport.runtime;

/**
 * Adapter-owned side channel for sending raw transport messages to a concrete
 * online worker endpoint without leaking adapter delivery DTOs into SDK
 * composition.
 *
 * <p>This is an operational/debug surface only. It must not become product
 * lifecycle truth or a second task-control mainline.
 */
public interface RawWorkerMessageChannel {

    /**
     * Returns the concrete adapter identity served by this raw side channel.
     */
    String adapterId();

    /**
     * Returns whether this channel can confidently route a raw message to the
     * given transport route under the current runtime state.
     */
    default boolean supportsRoute(String routeKey, String workerAdapterId) {
        return supportsAdapter(workerAdapterId);
    }

    /**
     * Returns whether the requested adapter id resolves to this side-channel.
     */
    default boolean supportsAdapter(String workerAdapterId) {
        return adapterId() != null
                && workerAdapterId != null
                && adapterId().equalsIgnoreCase(workerAdapterId.trim());
    }

    /**
     * Returns whether a route key can be used for endpoint lookup.
     */
    default boolean hasRouteKey(String routeKey) {
        return routeKey != null && !routeKey.isBlank();
    }

    /**
     * Sends a raw transport payload to a concrete route-addressed endpoint.
     */
    void sendToRoute(String routeKey, String rawJson, String traceId);
}
