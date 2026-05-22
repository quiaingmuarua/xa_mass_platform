package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedisTaskDispatchHandoffTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> producerConnection;
    private StatefulRedisConnection<String, String> consumerConnection;
    private String namespacePrefix;
    private RedisTaskDispatchHandoff producer;
    private RedisTaskDispatchHandoff consumer;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            producerConnection = redisClient.connect();
            consumerConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for dispatch handoff test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:dispatch-handoff:" + UUID.randomUUID();
        producer = new RedisTaskDispatchHandoff(producerConnection, namespacePrefix, 2);
        consumer = new RedisTaskDispatchHandoff(consumerConnection, namespacePrefix, 2);
        producer.clearForTest();
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.clearForTest();
            producer.shutdown();
        }
        if (consumer != null) {
            consumer.shutdown();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void dispatchBatchRoundTripsAcrossInstances() throws Exception {
        producer.submit(batch("task-1", "msg-1"));

        TaskDispatchBatch decoded = consumer.poll(1000L);

        assertNotNull(decoded);
        assertEquals("task-1", decoded.task().taskId());
        assertEquals("msg-1", decoded.dispatchBindings().getFirst().messageId());
        assertEquals(Map.of("target", "https://example.test"),
                decoded.dispatchBindings().getFirst().payload());
    }

    @Test
    void capacityBlocksUntilConsumerDrainsBacklog() throws Exception {
        producer.submit(batch("task-1", "msg-1"));
        producer.submit(batch("task-1", "msg-2"));

        CompletableFuture<Void> blockedSubmit = CompletableFuture.runAsync(
                () -> producer.submit(batch("task-1", "msg-3"))
        );

        Thread.sleep(200L);
        assertFalse(blockedSubmit.isDone());

        assertEquals("msg-1", consumer.poll(1000L).dispatchBindings().getFirst().messageId());
        blockedSubmit.get(2L, TimeUnit.SECONDS);
        assertEquals(2, producer.queuedBatches());
    }

    @Test
    void shutdownDoesNotClearSharedQueue() throws Exception {
        producer.submit(batch("task-2", "msg-1"));
        producer.shutdown();
        producer = null;

        TaskDispatchBatch decoded = consumer.poll(1000L);

        assertNotNull(decoded);
        assertEquals("msg-1", decoded.dispatchBindings().getFirst().messageId());
    }

    private static TaskDispatchBatch batch(String taskId, String messageId) {
        return new TaskDispatchBatch(
                new TaskDispatchContext(
                        taskId,
                        "task-name",
                        "demo-project",
                        "demo-user",
                        "demo.event",
                        Map.of("routingCode", "us")
                ),
                List.of(new TaskDispatchBinding(
                        taskId,
                        messageId,
                        "demo.event",
                        Map.of("target", "https://example.test"),
                        null,
                        0,
                        "attempt-" + messageId,
                        1,
                        "lease-" + messageId,
                        "worker-1",
                        "batch-1"
                ))
        );
    }
}
