package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseMetadata;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportEndpointLeaseStoreTest {

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;
    private RedisTransportEndpointLeaseStore store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for endpoint lease test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-endpoint-lease:" + UUID.randomUUID();
        store = new RedisTransportEndpointLeaseStore(connection, namespacePrefix, 1_000L);
    }

    @AfterEach
    void tearDown() {
        if (observerCommands != null) {
            for (String key : observerCommands.keys(namespacePrefix + ":*")) {
                observerCommands.del(key);
            }
        }
        if (store != null) {
            store.shutdown();
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void heartbeatRoundTripUsesBucketWorkersAndDeadlinesOnly() {
        store.claimEndpointLease(claim(" worker-1 ", " bucket-a ", " websocket ", " route-1 ", " conn-1 "));

        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("websocket", view.endpointDriverId());
        assertEquals("route-1", view.endpointAddress());
        assertEquals("conn-1", view.endpointLeaseId());
        assertNotNull(observerCommands.hget(bucketWorkersKey("bucket-a"), "worker-1"));
        assertTrue(observerCommands.zscore(deadlineKey("bucket-a"), "worker-1") > 0D);
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-1")));
        assertFalse(observerCommands.exists(routeConsumersKey("route-1")) > 0L);

        store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));
        assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isPresent());

        store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));

        assertTrue(store.currentEndpointLease("bucket-a", "worker-1").isEmpty());
        assertNull(observerCommands.hget(bucketWorkersKey("bucket-a"), "worker-1"));
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-1")));
        assertFalse(observerCommands.exists(routeConsumersKey("route-1")) > 0L);
    }

    @Test
    void reconnectStaleHeartbeatAndReleaseDoNotRemoveCurrentLease() {
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old"));
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-new", "conn-new"));

        assertTrue(store.refreshEndpointLease(heartbeat("worker-1", "bucket-a", "websocket", "route-old", "conn-old"))
                .isEmpty());
        assertFalse(store.releaseEndpointLease(release("worker-1", "bucket-a", "websocket", "route-old", "conn-old")));

        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("route-new", view.endpointAddress());
        assertEquals("conn-new", view.endpointLeaseId());
    }

    @Test
    void staleCasReleaseCannotDeleteReplacementLeaseAfterInterleaving() throws Exception {
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old"));
        TransportEndpointLeaseMetadata stale = metadata("worker-1", "bucket-a", "websocket", "route-old", "conn-old");
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-new", "conn-new"));

        assertFalse(invokeRemoveIfCurrent(stale));

        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("route-new", view.endpointAddress());
        assertEquals("conn-new", view.endpointLeaseId());
    }

    @Test
    void staleCasRefreshCannotExtendReplacementLeaseAfterInterleaving() throws Exception {
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old"));
        TransportEndpointLeaseMetadata stale = metadata("worker-1", "bucket-a", "websocket", "route-old", "conn-old");
        store.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-new", "conn-new"));
        Double replacementDeadline = observerCommands.zscore(deadlineKey("bucket-a"), "worker-1");

        assertFalse(invokeRefreshIfCurrent(stale, System.currentTimeMillis() + 60_000L));

        assertEquals(replacementDeadline, observerCommands.zscore(deadlineKey("bucket-a"), "worker-1"));
        var view = store.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
        assertEquals("route-new", view.endpointAddress());
        assertEquals("conn-new", view.endpointLeaseId());
    }

    @Test
    void staleDeadlineCleanupCannotDeleteReplacementLeaseAfterDueListRace() throws Exception {
        try (RedisTransportEndpointLeaseStore shortLeaseStore =
                     new RedisTransportEndpointLeaseStore(redisClient, namespacePrefix, 500L, false)) {
            shortLeaseStore.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-old", "conn-old"));
            Thread.sleep(550L);
            long staleCleanupClock = System.currentTimeMillis();
            shortLeaseStore.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-new", "conn-new"));

            assertFalse(invokeRemoveIfDeadlineDue(shortLeaseStore, "bucket-a", "worker-1", staleCleanupClock));

            var view = shortLeaseStore.currentEndpointLease("bucket-a", "worker-1").orElseThrow();
            assertEquals("route-new", view.endpointAddress());
            assertEquals("conn-new", view.endpointLeaseId());
        }
    }

    @Test
    void expiredLeasePrunesWithinOneBucketOnly() throws Exception {
        try (RedisTransportEndpointLeaseStore shortLeaseStore =
                     new RedisTransportEndpointLeaseStore(redisClient, namespacePrefix, 250L, false)) {
            shortLeaseStore.claimEndpointLease(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1"));
            shortLeaseStore.claimEndpointLease(claim("worker-2", "bucket-b", "websocket", "route-2", "conn-2"));

            Thread.sleep(300L);

            assertEquals(1, shortLeaseStore.pruneExpired("bucket-a", 10));
            assertNull(observerCommands.hget(bucketWorkersKey("bucket-a"), "worker-1"));
            assertNotNull(observerCommands.hget(bucketWorkersKey("bucket-b"), "worker-2"));
            assertEquals(1, shortLeaseStore.pruneExpired("bucket-b", 10));
        }
    }

    @Test
    void sharedNamespaceAllowsAnotherTransportInstanceToReadCurrentEndpointLease() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisTransportEndpointLeaseStore secondary =
                     new RedisTransportEndpointLeaseStore(secondaryConnection, namespacePrefix, 1_000L)) {
            store.claimEndpointLease(claim("worker-4", "bucket-a", "websocket", "route-4", "conn-4"));

            var mirrored = secondary.currentEndpointLease("bucket-a", "worker-4").orElseThrow();
            assertEquals("route-4", mirrored.endpointAddress());

            secondary.releaseEndpointLease(release("worker-4", "bucket-a", "websocket", "route-4", "conn-4"));

            assertTrue(store.currentEndpointLease("bucket-a", "worker-4").isEmpty());
        }
    }

    private static TransportEndpointLeaseClaim claim(String workerId,
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

    private static TransportEndpointLeaseHeartbeat heartbeat(String workerId,
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

    private static TransportEndpointLeaseRelease release(String workerId,
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

    private static TransportEndpointLeaseMetadata metadata(String workerId,
                                                           String deliveryBucketId,
                                                           String endpointDriverId,
                                                           String endpointAddress,
                                                           String sessionHandle) {
        return new TransportEndpointLeaseMetadata(
                deliveryBucketId,
                workerId,
                endpointDriverId,
                sessionHandle,
                sessionHandle,
                endpointAddress
        );
    }

    private boolean invokeRefreshIfCurrent(TransportEndpointLeaseMetadata metadata, long deadline) throws Exception {
        Method method = RedisTransportEndpointLeaseStore.class
                .getDeclaredMethod("refreshIfCurrent", TransportEndpointLeaseMetadata.class, long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(store, metadata, deadline);
    }

    private boolean invokeRemoveIfCurrent(TransportEndpointLeaseMetadata metadata) throws Exception {
        Method method = RedisTransportEndpointLeaseStore.class
                .getDeclaredMethod("removeIfCurrent", TransportEndpointLeaseMetadata.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(store, metadata);
    }

    private static boolean invokeRemoveIfDeadlineDue(RedisTransportEndpointLeaseStore store,
                                                     String deliveryBucketId,
                                                     String workerId,
                                                     long nowEpochMillis) throws Exception {
        Method method = RedisTransportEndpointLeaseStore.class
                .getDeclaredMethod("removeIfDeadlineDue", String.class, String.class, long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(store, deliveryBucketId, workerId, nowEpochMillis);
    }

    private String bucketWorkersKey(String deliveryBucketId) {
        return namespacePrefix + ":bucket:" + encode(deliveryBucketId) + ":workers";
    }

    private String deadlineKey(String deliveryBucketId) {
        return namespacePrefix + ":bucket:" + encode(deliveryBucketId) + ":deadlines";
    }

    private String routeConsumersKey(String routeKey) {
        return namespacePrefix + ":route:" + encode(routeKey) + ":consumers";
    }

    private String oldBucketWorkerPointerKey(String deliveryBucketId, String workerId) {
        return namespacePrefix
                + ":bucket:" + encode(deliveryBucketId)
                + ":worker:" + encode(workerId)
                + ":owner";
    }

    private static String encode(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
