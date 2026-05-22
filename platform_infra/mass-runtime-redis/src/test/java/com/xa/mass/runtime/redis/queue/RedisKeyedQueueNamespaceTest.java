package com.xa.mass.runtime.redis.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyedQueueNamespaceTest {

    @Test
    void namespaceBuildsStableQueueKeys() {
        RedisKeyedQueueNamespace namespace = new RedisKeyedQueueNamespace("mass:transport:delivery");

        assertEquals("mass:transport:delivery:q:websocket:worker-1", namespace.queueKey("websocket:worker-1"));
        assertEquals("mass:transport:delivery:meta:websocket:worker-1", namespace.metaKey("websocket:worker-1"));
        assertEquals("mass:transport:delivery:queues", namespace.activeQueuesKey());
        assertEquals("mass:transport:delivery:stats", namespace.globalStatsKey());
    }

    @Test
    void optionsDefaultsUseExpectedDurations() {
        RedisKeyedQueueOptions options = RedisKeyedQueueOptions.defaults(1000);

        assertEquals(1000, options.maxQueuedItems());
        assertEquals(Duration.ofMillis(100), options.pollSleepInterval());
        assertEquals(Duration.ofMillis(250), options.snapshotCacheWindow());
    }
}
