package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportDispatchFailureChannelTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> writerConnection;
    private StatefulRedisConnection<String, String> readerConnection;
    private String namespacePrefix;
    private RedisTransportDispatchFailureChannel writer;
    private RedisTransportDispatchFailureChannel reader;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            writerConnection = redisClient.connect();
            readerConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for dispatch failure test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:dispatch-failure:" + UUID.randomUUID();
        writer = new RedisTransportDispatchFailureChannel(writerConnection, namespacePrefix, 1);
        reader = new RedisTransportDispatchFailureChannel(readerConnection, namespacePrefix, 1);
        writer.clearForTest();
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.clearForTest();
            writer.shutdown();
        }
        if (reader != null) {
            reader.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void failureEventRoundTripsAcrossInstances() throws Exception {
        assertTrue(writer.compensate(task(), List.of(binding("msg-1")), "adapter unavailable"));

        TransportDispatchFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals("task-1", event.task().taskId());
        assertEquals("msg-1", event.dispatchBindings().getFirst().messageId());
        assertEquals("adapter unavailable", event.detail());
    }

    @Test
    void fullInboxRejectsWithoutDroppingExistingFailure() throws Exception {
        assertTrue(writer.compensate(task(), List.of(binding("msg-1")), "first"));
        assertFalse(writer.compensate(task(), List.of(binding("msg-2")), "second"));

        TransportDispatchFailureEvent event = reader.pollFailure(1000L);

        assertNotNull(event);
        assertEquals("msg-1", event.dispatchBindings().getFirst().messageId());
    }

    private static TaskDispatchContext task() {
        return new TaskDispatchContext(
                "task-1",
                "task-name",
                "demo-project",
                "demo-user",
                "demo.event",
                Map.of("routingCode", "us")
        );
    }

    private static TaskDispatchBinding binding(String messageId) {
        return new TaskDispatchBinding(
                "task-1",
                messageId,
                "demo.event",
                Map.of("target", "https://example.test"),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                "worker-1",
                "ctx-1",
                "batch-1"
        );
    }
}
