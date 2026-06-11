package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportRouteOwnerStoreTest {

    @Test
    void expiredRouteConsumerEvidenceDropsReachabilityView() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(25L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");

        assertTrue(store.isWorkerReachable("worker-1"));
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());
        assertEquals(1, store.listActiveRouteOwners().size());

        Thread.sleep(40L);

        TransportRouteOwnerRecord owner = store.getLatestOwnerByWorker("worker-1");
        assertNotNull(owner);
        assertFalse(owner.isLeaseActive(System.currentTimeMillis()));
        assertFalse(store.isWorkerReachable("worker-1"));
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.listActiveRouteOwners().isEmpty());
        assertEquals(1, store.pruneExpired());
        assertEquals(0, store.pruneExpired());
        assertEquals(0, store.currentOwners("route-1").size());
        assertNull(store.getLatestOwnerByWorker("worker-1"));
    }

    @Test
    void sameRouteKeyCanHaveMultipleActiveConsumers() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.claimRouteOwner("worker-2", "socket", "route-1", "conn-2", "connected");

        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(2, store.currentOwners("route-1").size());
        assertEquals(1, store.findRouteOwners("worker-1").size());
        assertEquals(1, store.findRouteOwners("worker-2").size());

        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());
    }

    @Test
    void selectedWorkerLookupUsesAdapterWorkerIndexUnderSharedRoute() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "shared-route", "conn-1", "connected");
        store.claimRouteOwner("worker-2", "websocket", "shared-route", "conn-2", "connected");

        assertEquals("conn-2", store.activeOwnerForSelectedWorker("websocket", "worker-2")
                .orElseThrow()
                .connectionId());
        assertEquals("runtime-a", store.activeOwnerForSelectedWorker("websocket", "worker-2")
                .orElseThrow()
                .transportNodeId());

        store.releaseRouteOwner("worker-2", "websocket", "shared-route", "conn-2", "disconnect");

        assertTrue(store.activeOwnerForSelectedWorker("websocket", "worker-2").isEmpty());
        assertEquals("conn-1", store.activeOwnerForSelectedWorker("websocket", "worker-1")
                .orElseThrow()
                .connectionId());
    }

    @Test
    void workerProjectionFindOwnersReturnsOnlyLatestOnlineRoute() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-old", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-new", "conn-new", "connected");

        assertEquals(1, store.findRouteOwners("worker-1").size());
        assertEquals("route-new", store.findRouteOwners("worker-1").getFirst().routeKey());
        assertEquals("route-old", store.currentOwner("route-old").orElseThrow().routeKey());
    }

    @Test
    void sameRouteReconnectReleaseOnlyRemovesMatchingConsumer() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-new", "reconnected");

        assertEquals(2, store.currentOwners("route-1").size());
        TransportRouteOwnerRecord oldRelease =
                store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-old", "old-disconnect");
        assertNotNull(oldRelease);
        assertEquals("conn-old", oldRelease.getConnectionId());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());

        TransportRouteOwnerRecord finalRelease =
                store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalRelease);
        assertEquals("conn-new", finalRelease.getConnectionId());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }
}
