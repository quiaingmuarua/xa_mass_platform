package com.xa.mass.transport.runtime.dispatch;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisRouteTargetedTaskDispatchHandoffTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> producerConnection;
    private StatefulRedisConnection<String, String> consumerOneConnection;
    private StatefulRedisConnection<String, String> consumerTwoConnection;
    private String namespacePrefix;
    private RedisRouteTargetedTaskDispatchHandoff producer;
    private RedisRouteTargetedTaskDispatchHandoff nodeOneConsumer;
    private RedisRouteTargetedTaskDispatchHandoff nodeTwoConsumer;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            producerConnection = redisClient.connect();
            consumerOneConnection = redisClient.connect();
            consumerTwoConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for route dispatch test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-route-dispatch:" + UUID.randomUUID();
        producer = new RedisRouteTargetedTaskDispatchHandoff(producerConnection, namespacePrefix, "producer", 2);
        nodeOneConsumer = new RedisRouteTargetedTaskDispatchHandoff(consumerOneConnection, namespacePrefix, "node-1", 2);
        nodeTwoConsumer = new RedisRouteTargetedTaskDispatchHandoff(consumerTwoConnection, namespacePrefix, "node-2", 2);
    }

    @AfterEach
    void tearDown() {
        if (producerConnection != null && producerConnection.isOpen()) {
            producerConnection.sync().keys(namespacePrefix + ":*").forEach(key -> producerConnection.sync().del(key));
        }
        if (producer != null) {
            producer.shutdown();
        }
        if (nodeOneConsumer != null) {
            nodeOneConsumer.shutdown();
        }
        if (nodeTwoConsumer != null) {
            nodeTwoConsumer.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void submitWakesOnlyReadyTransportNodes() throws Exception {
        RouteTargetedTaskDispatchBatch batch = RouteTargetedDispatchFixtures.batch(
                "route-1",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("msg-1", "worker-1")
        );

        producer.submit(batch);

        RouteTargetedTaskDispatchBatch nodeOne = nodeOneConsumer.poll(500L);
        RouteTargetedTaskDispatchBatch nodeTwo = nodeTwoConsumer.poll(0L);

        assertNotNull(nodeOne);
        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(nodeOne));
        assertNull(nodeTwo);
    }

    @Test
    void sameRouteBatchesArePartitionedByTargetTransportNode() throws Exception {
        producer.submit(RouteTargetedDispatchFixtures.batch(
                "group-route",
                "node-2",
                RouteTargetedDispatchFixtures.delivery("group-route", "node-2", "msg-2", "worker-2")
        ));
        producer.submit(RouteTargetedDispatchFixtures.batch(
                "group-route",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("group-route", "node-1", "msg-1", "worker-1")
        ));

        RouteTargetedTaskDispatchBatch nodeOne = nodeOneConsumer.poll(500L);
        RouteTargetedTaskDispatchBatch nodeTwo = nodeTwoConsumer.poll(500L);

        assertNotNull(nodeOne);
        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(nodeOne));
        assertNotNull(nodeTwo);
        assertEquals(List.of("msg-2"), RouteTargetedDispatchFixtures.messages(nodeTwo));
    }

    @Test
    void sharedRouteBatchesUseAdapterLaneQueueKeys() throws Exception {
        producer.submit(RouteTargetedDispatchFixtures.batch(
                "group-route",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("group-route", "node-1", "msg-1", "worker-1")
        ));
        producer.submit(RouteTargetedDispatchFixtures.batch(
                "group-route",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("group-route", "node-1", "msg-2", "worker-2")
        ));

        List<String> keys = producerConnection.sync().keys(namespacePrefix + ":*");

        assertTrue(keys.stream().anyMatch(key -> key.contains(":lane:")));
        assertTrue(keys.stream().anyMatch(key -> key.endsWith(":ready-lanes")));
        assertFalse(keys.stream().anyMatch(key -> key.contains(":route:")));
        assertFalse(keys.stream().anyMatch(key -> key.endsWith(":ready-routes")));
        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(nodeOneConsumer.poll(500L)));
        assertEquals(List.of("msg-2"), RouteTargetedDispatchFixtures.messages(nodeOneConsumer.poll(500L)));
        assertNull(nodeTwoConsumer.poll(0L));
    }

    @Test
    void rejectsBatchWithoutTargetTransportNode() {
        assertThrows(IllegalArgumentException.class, () -> RouteTargetedDispatchFixtures.batch(
                "route-1",
                " ",
                RouteTargetedDispatchFixtures.delivery("msg-1", "worker-1")
        ));
    }
}
