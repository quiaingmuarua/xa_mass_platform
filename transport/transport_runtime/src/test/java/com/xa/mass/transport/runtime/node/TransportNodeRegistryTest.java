package com.xa.mass.transport.runtime.node;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportNodeRegistryTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;
    private String namespacePrefix;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            commands = connection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for node registry test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-node:" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (commands != null) {
            for (String key : commands.keys(namespacePrefix + ":*")) {
                commands.del(key);
            }
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void redisRegistryTracksOnlineHeartbeatOfflineAndStaleState() throws Exception {
        RedisTransportNodeRegistry registry = new RedisTransportNodeRegistry(connection, namespacePrefix, 250L);
        registry.register("node-1", List.of("websocket"), 12L);

        assertTrue(registry.isNodeOnline("node-1"));
        assertEquals(List.of("websocket"), registry.getNode("node-1").adapterIds());
        assertEquals(12L, registry.getNode("node-1").connectionCount());

        registry.heartbeat("node-1", List.of("websocket", "socket"), 18L);
        assertTrue(registry.isNodeOnline("node-1"));
        assertEquals(List.of("websocket", "socket"), registry.getNode("node-1").adapterIds());

        registry.releaseRouteOwner("node-1");
        assertFalse(registry.isNodeOnline("node-1"));

        registry.register("node-2", List.of("polling"), 1L);
        Thread.sleep(300L);
        TransportNodePresence stale = registry.getNode("node-2");
        assertEquals(TransportNodeState.STALE, stale.state());
        assertFalse(registry.isNodeOnline("node-2"));
    }

    @Test
    void heartbeatLifecycleRegistersRefreshesAndMarksNodeOffline() throws Exception {
        InMemoryTransportNodeRegistry registry = new InMemoryTransportNodeRegistry(1_000L);
        AtomicLong connectionCount = new AtomicLong(3L);
        TransportNodeRegistryHeartbeat heartbeat = new TransportNodeRegistryHeartbeat(
                registry,
                "node-heartbeat",
                List.of("websocket"),
                connectionCount::get,
                10L
        );

        heartbeat.start();

        assertTrue(registry.isNodeOnline("node-heartbeat"));
        assertEquals(3L, registry.getNode("node-heartbeat").connectionCount());

        connectionCount.set(7L);
        Thread.sleep(80L);

        assertEquals(7L, registry.getNode("node-heartbeat").connectionCount());

        heartbeat.stop();

        assertFalse(registry.isNodeOnline("node-heartbeat"));
        assertEquals(TransportNodeState.OFFLINE, registry.getNode("node-heartbeat").state());
    }
}
