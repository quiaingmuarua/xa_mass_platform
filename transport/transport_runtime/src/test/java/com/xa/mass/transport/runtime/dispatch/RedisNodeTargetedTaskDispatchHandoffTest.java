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
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisNodeTargetedTaskDispatchHandoffTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> producerConnection;
    private StatefulRedisConnection<String, String> consumerOneConnection;
    private StatefulRedisConnection<String, String> consumerTwoConnection;
    private String namespacePrefix;
    private RedisNodeTargetedTaskDispatchHandoff producer;
    private RedisNodeTargetedTaskDispatchHandoff nodeOneConsumer;
    private RedisNodeTargetedTaskDispatchHandoff nodeTwoConsumer;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            producerConnection = redisClient.connect();
            consumerOneConnection = redisClient.connect();
            consumerTwoConnection = redisClient.connect();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for node-targeted dispatch test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:dispatch-node:" + UUID.randomUUID();
        producer = new RedisNodeTargetedTaskDispatchHandoff(producerConnection, namespacePrefix, null, 2);
        nodeOneConsumer = new RedisNodeTargetedTaskDispatchHandoff(consumerOneConnection, namespacePrefix, "node-1", 2);
        nodeTwoConsumer = new RedisNodeTargetedTaskDispatchHandoff(consumerTwoConnection, namespacePrefix, "node-2", 2);
        producer.clearForTest("node-1");
        producer.clearForTest("node-2");
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.clearForTest("node-1");
            producer.clearForTest("node-2");
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
    void nodeQueueIsOnlyConsumedByTargetNode() throws Exception {
        producer.submit("node-1", batch("task-1", "msg-1"));

        assertNull(nodeTwoConsumer.poll(25L));
        TaskDispatchBatch decoded = nodeOneConsumer.poll(1000L);

        assertNotNull(decoded);
        assertEquals("msg-1", decoded.dispatchBindings().getFirst().messageId());
    }

    @Test
    void fifoAndCapacityAreIndependentPerNode() throws Exception {
        producer.submit("node-1", batch("task-1", "msg-1"));
        producer.submit("node-1", batch("task-1", "msg-2"));
        producer.submit("node-2", batch("task-2", "msg-a"));

        CompletableFuture<Void> blockedSubmit = CompletableFuture.runAsync(
                () -> producer.submit("node-1", batch("task-1", "msg-3"))
        );

        Thread.sleep(200L);
        assertFalse(blockedSubmit.isDone());
        assertEquals("msg-a", nodeTwoConsumer.poll(1000L).dispatchBindings().getFirst().messageId());
        assertFalse(blockedSubmit.isDone());
        assertEquals("msg-1", nodeOneConsumer.poll(1000L).dispatchBindings().getFirst().messageId());
        blockedSubmit.get(2L, TimeUnit.SECONDS);
        assertEquals("msg-2", nodeOneConsumer.poll(1000L).dispatchBindings().getFirst().messageId());
        assertEquals("msg-3", nodeOneConsumer.poll(1000L).dispatchBindings().getFirst().messageId());
    }

    @Test
    void shutdownDoesNotClearSharedNodeQueue() throws Exception {
        producer.submit("node-1", batch("task-2", "msg-1"));
        producer.shutdown();
        producer = null;

        TaskDispatchBatch decoded = nodeOneConsumer.poll(1000L);

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
