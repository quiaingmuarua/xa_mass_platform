package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseMaintenance;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class TransportEndpointLeaseStoreContractTest {

    protected abstract LeaseStoreFixture createFixture(long leaseMillis);

    @Test
    void claimStoresBucketWorkerEndpointLease() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(30_000L)) {
            TransportEndpointLeaseStore store = fixture.store();

            var evidence = store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));

            assertEquals("bucket-a", evidence.deliveryBucketId());
            assertEquals("worker-1", evidence.workerId());
            assertEquals("websocket", evidence.endpointDriverId());
            assertEquals("conn-1", evidence.endpointLeaseId());
            assertTrue(evidence.leaseExpireAtEpochMillis() > System.currentTimeMillis());

            var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
            assertEquals("route-1", view.endpointAddress());
            assertEquals("conn-1", view.sessionHandle());
            assertEquals("conn-1", view.endpointLeaseId());
        }
    }

    @Test
    void matchingHeartbeatRefreshesConsumerEvidence() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(250L)) {
            TransportEndpointLeaseStore store = fixture.store();

            var first = store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));
            Thread.sleep(20L);
            var refreshed = store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "route-1", "conn-1"))
                    .orElseThrow();

            assertEquals(first.endpointLeaseId(), refreshed.endpointLeaseId());
            assertTrue(refreshed.leaseExpireAtEpochMillis() > first.leaseExpireAtEpochMillis());
        }
    }

    @Test
    void reconnectStaleHeartbeatAndReleaseCannotMoveCurrentLeaseBack() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(30_000L)) {
            TransportEndpointLeaseStore store = fixture.store();

            store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old"));
            store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-new", "conn-new"));

            assertTrue(store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "route-old", "conn-old"))
                    .isEmpty());
            assertFalse(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "route-old", "conn-old")));

            var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
            assertEquals("route-new", view.endpointAddress());
            assertEquals("conn-new", view.endpointLeaseId());
        }
    }

    @Test
    void releaseRequiresMatchingLeaseEvidence() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(30_000L)) {
            TransportEndpointLeaseStore store = fixture.store();

            store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));

            assertFalse(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "route-1", "conn-stale")));
            assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isPresent());
            assertTrue(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "route-1", "conn-1")));
            assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
        }
    }

    @Test
    void expiredLeaseIsRemovedByBucketScopedPrune() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(25L)) {
            TransportEndpointLeaseStore store = fixture.store();
            TransportEndpointLeaseMaintenance maintenance = fixture.maintenance();

            store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));
            Thread.sleep(40L);
            store.claimEndpointLease(claim("worker-2", "bucket-b", "websocket", "route-2", "conn-2"));

            assertEquals(1, maintenance.pruneExpired("bucket-a", 10));
            assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
            assertTrue(store.currentEndpointLease("bucket-b", "worker-2").isPresent());
            Thread.sleep(40L);
            assertEquals(1, maintenance.pruneExpired("bucket-b", 10));
            assertTrue(store.currentEndpointLease("bucket-b", "worker-2").isEmpty());
        }
    }

    @Test
    void currentEndpointLeaseDoesNotReturnExpiredLease() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(25L)) {
            TransportEndpointLeaseStore store = fixture.store();

            store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));
            Thread.sleep(40L);

            assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
        }
    }

    @Test
    void viewRecordDoesNotExposeLeaseTimestamps() {
        Set<String> components = Arrays.stream(TransportEndpointLeaseViewRecord.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(components.contains("leaseExpireAtEpochMillis"));
        assertFalse(components.contains("lastHeartbeatEpochMillis"));
        assertFalse(components.contains("updatedAtEpochMillis"));
    }

    @Test
    void claimRequiresDeliveryBucket() throws Exception {
        try (LeaseStoreFixture fixture = createFixture(30_000L)) {
            assertThrows(IllegalArgumentException.class, () ->
                    fixture.store().claimEndpointLease(claim("worker-1", " ", "websocket", "route-1", "conn-1")));
        }
    }

    protected static TransportEndpointLeaseClaim claim(String workerId,
                                                       String deliveryBucketId,
                                                       String endpointDriverId,
                                                       String endpointAddress,
                                                       String sessionHandle) {
        return new TransportEndpointLeaseClaim(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                endpointAddress,
                sessionHandle,
                "test"
        );
    }

    protected static TransportEndpointLeaseHeartbeat heartbeat(String workerId,
                                                               String deliveryBucketId,
                                                               String endpointDriverId,
                                                               String endpointAddress,
                                                               String sessionHandle) {
        return new TransportEndpointLeaseHeartbeat(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                endpointAddress,
                sessionHandle,
                "test"
        );
    }

    protected static TransportEndpointLeaseRelease release(String workerId,
                                                           String deliveryBucketId,
                                                           String endpointDriverId,
                                                           String endpointAddress,
                                                           String sessionHandle) {
        return new TransportEndpointLeaseRelease(
                workerId,
                deliveryBucketId,
                endpointDriverId,
                endpointAddress,
                sessionHandle,
                "test"
        );
    }

    protected record LeaseStoreFixture(TransportEndpointLeaseStore store,
                                       TransportEndpointLeaseMaintenance maintenance,
                                       AutoCloseable closeable) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (closeable != null) {
                closeable.close();
            }
        }
    }
}
