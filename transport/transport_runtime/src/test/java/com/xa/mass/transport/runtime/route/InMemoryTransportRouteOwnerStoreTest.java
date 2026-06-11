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
    void expiredOwnerEvidenceDropsRouteOnlineView() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(25L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");

        assertTrue(store.isWorkerReachable("worker-1"));
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
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
        assertEquals(0, store.listActiveRouteOwners().size());
        assertNull(store.getLatestOwnerByWorker("worker-1"));
    }

    @Test
    void routeKeyTakeoverReplacesCurrentOwnerAcrossAdapters() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-1", "conn-9", "reconnected");

        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(1, store.findRouteOwners("worker-1").size());
        assertEquals("socket", store.currentOwner("route-1").orElseThrow().adapterId());

        TransportRouteOwnerRecord activeOwner = store.getLatestOwnerByWorker("worker-1");
        assertNotNull(activeOwner);
        assertEquals("socket", activeOwner.getAdapterId());
        assertEquals("route-1", activeOwner.getRouteKey());
        assertTrue(activeOwner.isLeaseActive(System.currentTimeMillis()));

        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "stale-disconnect");
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));

        store.releaseRouteOwner("worker-1", "socket", "route-1", "conn-9", "disconnect");

        assertNull(store.getLatestOwnerByWorker("worker-1"));
        assertFalse(store.hasActiveRouteOwner("socket", "route-1"));
        assertTrue(store.findRouteOwners("worker-1").isEmpty());
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
    void reconnectOnSameRouteRejectsStaleHeartbeatAndDisconnect() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-new", "reconnected");

        TransportRouteOwnerRecord ignoredHeartbeat = store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-old", "stale-heartbeat");
        assertNotNull(ignoredHeartbeat);
        assertEquals("conn-new", ignoredHeartbeat.getConnectionId());
        assertEquals("route-1", ignoredHeartbeat.getRouteKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        TransportRouteOwnerRecord ignoredOffline = store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-old", "stale-disconnect");
        assertNotNull(ignoredOffline);
        assertEquals("conn-new", ignoredOffline.getConnectionId());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        TransportRouteOwnerRecord finalOffline = store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalOffline);
        assertEquals("conn-new", finalOffline.getConnectionId());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }
}
