package com.xa.mass.transport.route;

/**
 * Shared transport-owned route-owner heartbeat write surface.
 *
 * <p>For worker delivery, one opaque {@code routeKey} identifies a consumption
 * route that may have multiple active consumers. Claim, heartbeat refresh, and
 * release are consumer-checked evidence writes: heartbeat/release only mutate
 * the consumer addressed by {@code connectionId}. This prevents stale
 * disconnect or heartbeat events from an older connection from revoking a newer
 * active consumer on the same route.</p>
 *
 * <p>This write surface deliberately does not expose worker-id inspection.
 * Assigned delivery routing should depend on {@link WorkerDispatchRouteOwnerView};
 * worker-id read models belong to worker runtime/resource views.</p>
 */
public interface TransportRouteOwnerStore {

    TransportRouteOwnerRecord claimRouteOwner(String workerId,
                                   String adapterId,
                                   String routeKey,
                                   String connectionId,
                                   String reason);

    TransportRouteOwnerRecord refreshHeartbeat(String workerId,
                                    String adapterId,
                                    String routeKey,
                                    String connectionId,
                                    String reason);

    TransportRouteOwnerRecord releaseRouteOwner(String workerId,
                                     String adapterId,
                                     String routeKey,
                                     String connectionId,
                                     String reason);

    int pruneExpired();

    default long getLeaseMillis() {
        return 30_000L;
    }
}
