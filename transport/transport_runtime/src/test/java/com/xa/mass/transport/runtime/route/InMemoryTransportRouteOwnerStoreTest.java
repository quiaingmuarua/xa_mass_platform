package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportRouteOwnerStoreTest {

    @Test
    void expiredRouteConsumerEvidenceDropsBucketWorkerLookup() throws Exception {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(500L, "runtime-a");

        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1", "connected"));

        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-1").isPresent());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());

        Thread.sleep(650L);

        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-1").isEmpty());
        assertTrue(store.endpointForSelectedWorker("bucket-a", "worker-1").isEmpty());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.pruneExpired());
        assertEquals(0, store.pruneExpired());
        assertEquals(0, store.currentOwners("route-1").size());
    }

    @Test
    void sameRouteKeyCanHaveMultipleActiveConsumersAcrossBuckets() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1", "connected"));
        store.claimRouteOwner(claim("worker-2", "bucket-b", "socket", "route-1", "conn-2", "connected"));

        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(2, store.currentOwners("route-1").size());
        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-1").isPresent());
        assertTrue(store.targetForSelectedWorker("bucket-b", "worker-2").isPresent());

        store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1", "disconnect"));

        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());
        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-1").isEmpty());
        assertTrue(store.targetForSelectedWorker("bucket-b", "worker-2").isPresent());
    }

    @Test
    void bucketWorkerLookupUsesCurrentConsumerUnderSharedRoute() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "shared-route", "conn-1", "connected"));
        store.claimRouteOwner(claim("worker-2", "bucket-a", "websocket", "shared-route", "conn-2", "connected"));

        assertEquals("runtime-a", store.targetForSelectedWorker("bucket-a", "worker-2")
                .orElseThrow()
                .targetTransportNodeId());
        assertEquals("conn-2", store.endpointForSelectedWorker("bucket-a", "worker-2")
                .orElseThrow()
                .connectionId());

        store.releaseRouteOwner(claim("worker-2", "bucket-a", "websocket", "shared-route", "conn-2", "disconnect"));

        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-2").isEmpty());
        assertEquals("conn-1", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());
    }

    @Test
    void bucketWorkerLookupUsesLatestClaimedConsumerWithoutProtocolFallback() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old", "connected"));
        store.claimRouteOwner(claim("worker-1", "bucket-a", "socket", "route-new", "conn-new", "connected"));

        assertEquals("route-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .routeKey());
        assertEquals("socket", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .adapterId());
        assertEquals("route-old", store.currentOwner("route-old").orElseThrow().routeKey());
    }

    @Test
    void sameBucketWorkerReconnectReleaseOnlyRemovesMatchingConsumer() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "connected"));
        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-new", "reconnected"));

        assertEquals(2, store.currentOwners("route-1").size());
        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());

        store.refreshHeartbeat(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "late-heartbeat"));

        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());
        TransportRouteOwnerRecord oldRelease =
                store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "old-disconnect"));
        assertNotNull(oldRelease);
        assertEquals("conn-old", oldRelease.getConnectionId());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.currentOwners("route-1").size());
        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());

        TransportRouteOwnerRecord finalRelease =
                store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-new", "disconnect"));
        assertNotNull(finalRelease);
        assertEquals("conn-new", finalRelease.getConnectionId());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.endpointForSelectedWorker("bucket-a", "worker-1").isEmpty());
    }

    @Test
    void claimRequiresDeliveryBucket() {
        InMemoryTransportRouteOwnerStore store = new InMemoryTransportRouteOwnerStore(30_000L, "runtime-a");

        assertThrows(IllegalArgumentException.class, () ->
                store.claimRouteOwner(claim("worker-1", " ", "websocket", "route-1", "conn-1", "connected")));
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
