package com.xa.mass.transport.route;

/**
 * Shared transport-owned route-owner heartbeat write surface.
 *
 * <p>For assigned worker delivery, {@code deliveryBucketId + workerId}
 * identifies the current consumer projection. Route and connection facts are
 * endpoint evidence owned by transport. Claim, heartbeat refresh, and release
 * are consumer-checked writes: heartbeat/release only mutate the current
 * consumer when the route/connection evidence still matches.</p>
 *
 * <p>This write surface deliberately does not expose worker-id inspection.
 * Assigned delivery routing should depend on {@link WorkerDispatchRouteOwnerView};
 * worker-id read models belong to worker runtime/resource views.</p>
 */
public interface TransportRouteOwnerStore {

    TransportRouteOwnerRecord claimRouteOwner(TransportRouteOwnerClaim claim);

    TransportRouteOwnerRecord refreshHeartbeat(TransportRouteOwnerClaim claim);

    TransportRouteOwnerRecord releaseRouteOwner(TransportRouteOwnerClaim claim);

    int pruneExpired();

    default long getLeaseMillis() {
        return 30_000L;
    }
}
