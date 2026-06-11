package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.runtime.mode=redis",
                "mass.engine.assignment-retry-delay-millis=100",
                "mass.engine.runtime-ready-dispatch-idle-backoff-max-millis=500",
                "mass.engine.lease-watchdog-interval-seconds=1",
                "mass.engine.task-message-lease-seconds=2"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TaskApiDelayedWorkerAvailabilityRedisRuntimeIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String REDIS_URI = "redis://127.0.0.1:6379/0";
    private static final String RUNTIME_NAMESPACE = "xa:mass:test:late-worker-redis:" + UUID.randomUUID();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("spring.redis.host", () -> "127.0.0.1");
        registry.add("spring.redis.port", () -> 6379);
        registry.add("spring.redis.database", () -> 0);
        registry.add("mass.runtime.redis.namespace", () -> RUNTIME_NAMESPACE);
    }

    @AfterAll
    static void cleanupRedisNamespace() {
        RedisClient redisClient = RedisClient.create(REDIS_URI);
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            List<String> keys = connection.sync().keys(RUNTIME_NAMESPACE + ":*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void redisRuntimeBackfillsReadyTaskWhenWorkerRegistersLate() throws Exception {
        String taskId = createTaskId(
                "late-worker-redis",
                "late worker redis runtime proof",
                "target-a"
        );
        assertApiOk(approveTask(taskId));

        RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(1, readySnapshot.stats().readyCount());
        assertEquals(0, readySnapshot.stats().inflightCount());
        assertTrue(readySnapshot.activeLeases().isEmpty());

        String lateWorkerId = "late-worker-redis-0";
        registerSdkWorkerWithContext(lateWorkerId, "us");
        assertFalse(app.isWorkerOnline(lateWorkerId),
                "worker registration must not create transport presence");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = sampleWebSocketClient(uri, "us", lateWorkerId);
        try {
            assertClientConnects(client, "late worker redis client failed to connect");
            waitUntil(() -> app.isWorkerOnline(lateWorkerId),
                    "late worker connect must surface transport presence online");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, terminalSnapshot.stats().successCount());
        } finally {
            client.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(lateWorkerId),
                "late worker disconnect must converge transport presence offline");
    }

    @Test
    void redisRuntimeRefillsSingleWorkerTaskAcrossMultipleDispatchRounds() throws Exception {
        String workerId = "redis-refill-worker-0";
        registerSdkWorkerWithContext(workerId, "us");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = sampleWebSocketClient(uri, "us", workerId);
        try {
            assertClientConnects(client, "redis refill worker client failed to connect");
            waitUntil(() -> app.isWorkerOnline(workerId),
                    "redis refill worker connect must surface transport presence online");

            String taskId = createTaskId(
                    "redis-refill-single-worker",
                    "redis runtime refill proof",
                    List.of("target-a", "target-b", "target-c"),
                    1
            );
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 30, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(3, terminalSnapshot.stats().successCount());
            assertEquals(3, terminalSnapshot.stats().finalCount());
            assertEquals(0, terminalSnapshot.stats().readyCount());
            assertEquals(0, terminalSnapshot.stats().inflightCount());
            assertTrue(terminalSnapshot.activeLeases().isEmpty());
        } finally {
            client.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(workerId),
                "redis refill worker disconnect must converge transport presence offline");
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}
