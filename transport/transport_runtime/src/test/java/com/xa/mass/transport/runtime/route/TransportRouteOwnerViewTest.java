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
        assertTrue(store.activeOwnerForSelectedWorker("socket", "worker-1").isPresent());
        assertTrue(store.activeOwnerForSelectedWorker("websocket", "worker-1").isEmpty());
    }

    @Test
    void staleRouteDoesNotParticipateInDispatchOwnerView() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(25L, "node-1");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        Thread.sleep(40L);

        assertFalse(store.hasActiveOwner("route-1"));
        assertTrue(store.activeOwnerForSelectedWorker("websocket", "worker-1").isEmpty());
    }
}
