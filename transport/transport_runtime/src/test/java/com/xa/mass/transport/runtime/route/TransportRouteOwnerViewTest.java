package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRouteOwnerViewTest {

    @Test
    void unifiedViewReportsRouteReachableWhenCurrentOwnerIsOnline() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");

        store.claimRouteOwner(claim("worker-1", "bucket-1", "websocket", "route-1", "conn-1", "connected"));
        store.claimRouteOwner(claim("worker-1", "bucket-1", "socket", "route-1", "conn-2", "reconnected"));
        store.releaseRouteOwner(claim("worker-1", "bucket-1", "websocket", "route-1", "conn-1", "disconnect"));

        assertTrue(store.hasActiveOwner("route-1"));
        assertTrue(store.targetForSelectedWorker("bucket-1", "worker-1").isPresent());
        assertEquals("socket", store.endpointForSelectedWorker("bucket-1", "worker-1").orElseThrow().adapterId());
    }

    @Test
    void staleRouteDoesNotParticipateInDispatchOwnerView() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(25L, "node-1");

        store.claimRouteOwner(claim("worker-1", "bucket-1", "websocket", "route-1", "conn-1", "connected"));
        Thread.sleep(40L);

        assertFalse(store.hasActiveOwner("route-1"));
        assertTrue(store.targetForSelectedWorker("bucket-1", "worker-1").isEmpty());
    }

    private static TransportRouteOwnerClaim claim(String workerId,
                                                  String deliveryBucketId,
                                                  String adapterId,
                                                  String routeKey,
                                                  String connectionId,
                                                  String reason) {
        return new TransportRouteOwnerClaim(workerId, deliveryBucketId, adapterId, routeKey, connectionId, reason);
    }
}
