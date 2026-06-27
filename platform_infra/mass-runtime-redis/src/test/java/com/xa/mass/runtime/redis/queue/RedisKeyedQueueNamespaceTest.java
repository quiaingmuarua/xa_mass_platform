package com.xa.mass.runtime.redis.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyedQueueNamespaceTest {

    @Test
    void namespaceBuildsStableQueueKeys() {
        RedisKeyedQueueNamespace namespace = new RedisKeyedQueueNamespace("mass:transport:delivery");

        assertEquals("mass:transport:delivery:q:websocket:worker-1", namespace.queueKey("websocket:worker-1"));
    }

    @Test
    void optionsDefaultsUseExpectedDurations() {
        RedisKeyedQueueOptions options = RedisKeyedQueueOptions.defaults(1000);

        assertEquals(1000, options.maxQueuedItems());
        assertEquals(Duration.ofMillis(100), options.pollSleepInterval());
    }
}
