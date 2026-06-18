package com.xa.mass.transport;

/**
 * Route-only endpoint surface for raw/manual worker messages.
 *
 * <p>This is not an assigned task delivery API. Task dispatch must use concrete
 * adapter command executors and the engine-selected worker identity, so
 * transport cannot silently deliver an assigned item to an arbitrary endpoint
 * bound to the same route metadata.
 */
public interface RawWorkerRouteEndpointRegistry {

    boolean sendToAdapterRoute(String adapterId, String routeKey, String message);

    boolean isAdapterRouteOnline(String adapterId, String routeKey);
}
