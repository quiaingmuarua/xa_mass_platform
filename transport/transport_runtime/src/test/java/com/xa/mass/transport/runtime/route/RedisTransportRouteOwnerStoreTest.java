package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportRouteOwnerStoreTest {

    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;
    private RedisTransportRouteOwnerStore store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for route-owner test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-route-owner:" + UUID.randomUUID();
        store = new RedisTransportRouteOwnerStore(connection, namespacePrefix, 1_000L, "runtime-a");
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
    void heartbeatRoundTripUsesRouteConsumerHashAndDeadlineIndexOnly() {
        store.claimRouteOwner(claim(" worker-1 ", " bucket-a ", " websocket ", " route-1 ", " conn-1 ", "connected"));

        var endpoint = store.endpointForSelectedWorker("bucket-a", "worker-1").orElseThrow();
        assertTrue(endpoint.isActive(System.currentTimeMillis()));
        assertEquals("websocket", endpoint.adapterId());
        assertEquals("route-1", endpoint.routeKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals("worker-1", store.currentOwner("route-1").orElseThrow().workerId());
        assertFalse(observerCommands.exists(namespacePrefix + ":routes") > 0L);
        assertFalse(observerCommands.exists(workerRouteKey("worker-1")) > 0L);
        assertNull(observerCommands.get(legacyAdapterWorkerKey("websocket", "worker-1")));
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-1")));
        assertEquals(1, store.currentOwners("route-1").size());

        store.refreshHeartbeat(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1", "heartbeat"));
        assertTrue(store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-1", "disconnect"));

        assertTrue(store.endpointForSelectedWorker("bucket-a", "worker-1").isEmpty());
        assertTrue(store.currentOwners("route-1").isEmpty());
        assertFalse(observerCommands.exists(workerRouteKey("worker-1")) > 0L);
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-1")));
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }

    @Test
    void sameRouteKeyCanHaveMultipleActiveConsumersAcrossBuckets() {
        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-shared", "conn-1", "connected"));
        store.claimRouteOwner(claim("worker-2", "bucket-b", "socket", "route-shared", "conn-2", "connected"));

        assertTrue(store.hasActiveRouteOwner("websocket", "route-shared"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-shared"));
        assertEquals(2, store.currentOwners("route-shared").size());

        store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-shared", "conn-1", "disconnect"));

        assertFalse(store.hasActiveRouteOwner("websocket", "route-shared"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-shared"));
        assertEquals(1, store.currentOwners("route-shared").size());
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-1")));
        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-b", "worker-2")));
    }

    @Test
    void selectedWorkerInspectionLookupScansCurrentRouteConsumerEvidence() {
        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-shared", "conn-1", "connected"));
        store.claimRouteOwner(claim("worker-2", "bucket-a", "websocket", "route-shared", "conn-2", "connected"));

        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-2")));
        assertNull(observerCommands.get(legacyAdapterWorkerKey("websocket", "worker-2")));
        assertEquals("conn-2", store.endpointForSelectedWorker("bucket-a", "worker-2")
                .orElseThrow()
                .connectionId());
        assertEquals("runtime-a", store.targetForSelectedWorker("bucket-a", "worker-2")
                .orElseThrow()
                .targetTransportNodeId());

        store.releaseRouteOwner(claim("worker-2", "bucket-a", "websocket", "route-shared", "conn-2", "disconnect"));

        assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-2")));
        assertTrue(store.targetForSelectedWorker("bucket-a", "worker-2").isEmpty());
    }

    @Test
    void staleHeartbeatDoesNotMoveSelectedWorkerInspectionBackToOldConsumer() {
        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "connected"));
        store.claimRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-new", "reconnected"));

        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());

        store.refreshHeartbeat(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "late-heartbeat"));

        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());
        store.releaseRouteOwner(claim("worker-1", "bucket-a", "websocket", "route-1", "conn-old", "old-disconnect"));
        assertEquals("conn-new", store.endpointForSelectedWorker("bucket-a", "worker-1")
                .orElseThrow()
                .connectionId());
    }

    @Test
    void expiredConsumerEvidencePrunesDeadlineIndex() throws Exception {
        try (RedisTransportRouteOwnerStore shortLeaseStore =
                     new RedisTransportRouteOwnerStore(redisClient, namespacePrefix, 250L, "runtime-a", false)) {
            shortLeaseStore.claimRouteOwner(claim("worker-2", "bucket-a", "socket", "route-2", "conn-2", "connected"));

            assertTrue(shortLeaseStore.hasActiveRouteOwner("socket", "route-2"));
            Thread.sleep(300L);

            assertTrue(shortLeaseStore.targetForSelectedWorker("bucket-a", "worker-2").isEmpty());
            assertFalse(shortLeaseStore.hasActiveRouteOwner("socket", "route-2"));
            assertEquals(1, shortLeaseStore.pruneExpired());
            assertTrue(shortLeaseStore.currentOwners("route-2").isEmpty());
            assertNull(observerCommands.get(oldBucketWorkerPointerKey("bucket-a", "worker-2")));
        }
    }

    @Test
    void sharedNamespaceAllowsAnotherTransportInstanceToReadRouteConsumerTruth() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisTransportRouteOwnerStore secondary =
                     new RedisTransportRouteOwnerStore(secondaryConnection, namespacePrefix, 1_000L, "runtime-b")) {
            store.claimRouteOwner(claim("worker-4", "bucket-a", "websocket", "route-4", "conn-4", "connected"));

            var mirrored = secondary.endpointForSelectedWorker("bucket-a", "worker-4").orElseThrow();
            assertTrue(mirrored.isActive(System.currentTimeMillis()));
            assertEquals("websocket", mirrored.adapterId());
            assertEquals("route-4", mirrored.routeKey());
            assertTrue(secondary.hasActiveRouteOwner("websocket", "route-4"));
            assertEquals(1, secondary.currentOwners("route-4").size());

            secondary.releaseRouteOwner(claim("worker-4", "bucket-a", "websocket", "route-4", "conn-4", "disconnect"));

            assertTrue(store.endpointForSelectedWorker("bucket-a", "worker-4").isEmpty());
            assertFalse(store.hasActiveRouteOwner("websocket", "route-4"));
        }
    }

    private static TransportRouteOwnerClaim claim(String workerId,
                                                  String deliveryBucketId,
                                                  String adapterId,
                                                  String routeKey,
                                                  String connectionId,
                                                  String reason) {
        return new TransportRouteOwnerClaim(workerId, deliveryBucketId, adapterId, routeKey, connectionId, reason);
    }

    private String workerRouteKey(String workerId) {
        return namespacePrefix + ":worker-route:" + workerId;
    }

    private String legacyAdapterWorkerKey(String adapterId, String workerId) {
        return namespacePrefix
                + ":adapter:" + encode(adapterId)
                + ":worker:" + encode(workerId)
                + ":owner";
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
