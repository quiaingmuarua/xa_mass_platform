package com.xa.mass.transport.route;

/**
 * Shared transport-owned route-owner heartbeat write surface.
 *
 * <p>Route and connection facts are endpoint evidence owned by transport.
 * Claim, heartbeat refresh, and release are consumer-checked writes:
 * heartbeat/release only mutate matching route/connection evidence and must
 * not write worker lifecycle or assigned-delivery routing truth.</p>
 *
 * <p>This write surface deliberately does not expose worker lifecycle or
 * scheduling read models. Assigned delivery uses handoff-private
 * selected-worker consumer evidence; route-owner read views are diagnostics and
 * raw-route maintenance only.</p>
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
