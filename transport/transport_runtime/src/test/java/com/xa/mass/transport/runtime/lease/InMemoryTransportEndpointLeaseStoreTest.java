package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTransportEndpointLeaseStoreTest {

    @Test
    void claimStoresBucketWorkerEndpointLeaseWithoutRouteOwnerLookup() {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(30_000L);

        var evidence = store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-1"));

        assertEquals("bucket-a", evidence.deliveryBucketId());
        assertEquals("worker-1", evidence.workerId());
        assertEquals("websocket", evidence.endpointDriverId());
        assertEquals("conn-1", evidence.endpointLeaseId());
        assertTrue(evidence.leaseExpireAtEpochMillis() > System.currentTimeMillis());

        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("conn-1", view.sessionHandle());
        assertEquals("conn-1", view.endpointLeaseId());
    }

    @Test
    void reconnectStaleHeartbeatAndReleaseCannotMoveCurrentLeaseBack() {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(30_000L);

        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-old"));
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-new"));

        assertTrue(store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "conn-old"))
                .isEmpty());
        assertFalse(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "conn-old")));

        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("conn-new", view.sessionHandle());
        assertEquals("conn-new", view.endpointLeaseId());
    }

    @Test
    void matchingHeartbeatRefreshesConsumerEvidence() throws Exception {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(250L);

        var first = store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-1"));
        Thread.sleep(20L);
        var refreshed = store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "conn-1"))
                .orElseThrow();

        assertEquals(first.endpointLeaseId(), refreshed.endpointLeaseId());
        assertTrue(refreshed.leaseExpireAtEpochMillis() > first.leaseExpireAtEpochMillis());
    }

    @Test
    void expiredLeaseIsRemovedByBucketScopedPrune() throws Exception {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(25L);

        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-1"));
        store.claimEndpointLease(claim("worker-2", "bucket-b", "websocket", "conn-2"));
        Thread.sleep(40L);

        assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
        assertEquals(0, store.pruneExpired("bucket-a", 10));
        assertEquals(1, store.pruneExpired("bucket-b", 10));
    }

    @Test
    void releaseRequiresMatchingLeaseEvidence() {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(30_000L);

        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "conn-1"));

        assertFalse(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "conn-stale")));
        assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isPresent());
        assertTrue(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "conn-1")));
        assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
    }

    @Test
    void viewRecordDoesNotExposeLeaseTimestampsOrEndpointAddress() {
        Set<String> components = Arrays.stream(com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord.class
                        .getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(components.contains("leaseExpireAtEpochMillis"));
        assertFalse(components.contains("lastHeartbeatEpochMillis"));
        assertFalse(components.contains("updatedAtEpochMillis"));
        assertFalse(components.contains("runtimeNodeId"));
        assertFalse(components.contains("endpointAddress"));
    }

    @Test
    void claimRequiresDeliveryBucket() {
        InMemoryTransportEndpointLeaseStore store = new InMemoryTransportEndpointLeaseStore(30_000L);

        assertThrows(IllegalArgumentException.class, () ->
                store.claimEndpointLease(claim("worker-1", " ", "websocket", "conn-1")));
    }

    private static TransportEndpointLeaseClaim claim(String workerId,
                                                     String deliveryBucketId,
                                                     String endpointDriverId,
                                                     String sessionHandle) {
        return new TransportEndpointLeaseClaim(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                sessionHandle,
                "test"
        );
    }

    private static TransportEndpointLeaseHeartbeat heartbeat(String workerId,
                                                            String deliveryBucketId,
                                                            String endpointDriverId,
                                                            String sessionHandle) {
        return new TransportEndpointLeaseHeartbeat(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                sessionHandle,
                "test"
        );
    }

    private static TransportEndpointLeaseRelease release(String workerId,
                                                        String deliveryBucketId,
                                                        String endpointDriverId,
                                                        String sessionHandle) {
        return new TransportEndpointLeaseRelease(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                sessionHandle,
                "test"
        );
    }
}
