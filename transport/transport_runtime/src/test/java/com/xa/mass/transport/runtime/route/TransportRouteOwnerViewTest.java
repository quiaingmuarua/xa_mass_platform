package com.xa.mass.transport.runtime.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRouteOwnerViewTest {

    @Test
    void unifiedViewReportsRouteReachableWhenCurrentOwnerIsOnline() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-1", "conn-2", "reconnected");
        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        assertTrue(store.hasActiveOwner("route-1"));
        assertTrue(store.isWorkerReachable("worker-1"));
        assertEquals(1, store.findRouteOwners("worker-1").size());
        assertEquals("socket", store.findRouteOwners("worker-1").getFirst().adapterId());
    }

    @Test
    void staleRouteDoesNotParticipateInDispatchOwnerView() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(25L, "node-1");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        Thread.sleep(40L);

        assertFalse(store.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertFalse(store.hasActiveOwner("route-1"));
        assertTrue(store.findRouteOwners("worker-1").isEmpty());
    }
}
