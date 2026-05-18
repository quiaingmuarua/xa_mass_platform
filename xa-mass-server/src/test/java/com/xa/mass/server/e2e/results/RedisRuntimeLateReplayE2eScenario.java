package com.xa.mass.server.e2e.results;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                "mass.engine.lease-watchdog-interval-seconds=1",
                "mass.engine.task-message-lease-seconds=2"
        }
)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisRuntimeLateReplayE2eScenario extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String CHAOS_WORKER_ID = "redis-runtime-chaos-worker";
    private static final String STEADY_WORKER_ID = "redis-runtime-steady-worker";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("spring.redis.host", () -> "127.0.0.1");
        registry.add("spring.redis.port", () -> 6379);
        registry.add("spring.redis.database", () -> 0);
    }

    @Test
    void staleReplayAfterRedisLeaseExpiryDoesNotOverwriteTerminalHttpState() throws Exception {
        registerSdkWorkerWithContext(CHAOS_WORKER_ID, "us");
        registerSdkWorkerWithContext(STEADY_WORKER_ID, "us");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        DisconnectingWebSocketClient chaosClient = connectClientWithRetries(
                () -> new DisconnectingWebSocketClient(wsUri, CHAOS_WORKER_ID),
                "Chaos worker failed to connect"
        );
        try {
            waitUntil(() -> app.isWorkerOnline(CHAOS_WORKER_ID), "chaos worker must become online");

            String taskId = createTaskId("redis-runtime-late-replay", "redis runtime replay integration", List.of("target-a"), 1, 1);
            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            JsonObject firstDispatch = chaosClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "chaos worker should receive the first dispatch");
            chaosClient.disconnect();
            waitUntil(() -> !app.isWorkerOnline(CHAOS_WORKER_ID), "chaos worker disconnect must converge transport presence offline");

            SampleWorkerWebSocketClient steadyClient = connectClientWithRetries(
                    () -> new SampleWorkerWebSocketClient(wsUri, STEADY_WORKER_ID),
                    "steady worker failed to connect"
            );
            try {
                waitUntil(() -> app.isWorkerOnline(STEADY_WORKER_ID), "steady worker must become online");

                TaskSnapshot terminal = waitForTaskSnapshot(
                        taskId,
                        snapshot -> "TERMINAL".equals(snapshot.task().get("status"))
                                && snapshot.messages().size() == 1
                                && STEADY_WORKER_ID.equals(snapshot.messages().get(0).get("latestAttemptWorkerId")),
                        "TERMINAL after redis lease-expiry takeover by the steady worker",
                        40,
                        250L
                );
                assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
                assertEquals("SUCCESS", terminal.messages().get(0).get("status"));
                Object originalResult = terminal.messages().get(0).get("result");

                String messageId = String.valueOf(terminal.messages().get(0).get("messageId"));
                var attemptsBeforeReplay = fetchTaskMessageAttempts(taskId, messageId);
                assertEquals(2, attemptsBeforeReplay.size(), "redis runtime should produce one expired attempt and one success attempt");
                assertEquals("EXPIRED", attemptsBeforeReplay.get(0).get("status"));
                assertEquals("SUCCEEDED", attemptsBeforeReplay.get(1).get("status"));

                ReplayWebSocketClient replayClient = connectClientWithRetries(
                        () -> new ReplayWebSocketClient(wsUri, CHAOS_WORKER_ID),
                        "replay worker failed to reconnect"
                );
                try {
                    replayClient.sendMessage(WsFrameTestSupport.buildTaskResult(
                            WsFrameTestSupport.messageId(firstDispatch),
                            WsFrameTestSupport.project(firstDispatch),
                            CHAOS_WORKER_ID,
                            WsFrameTestSupport.taskId(firstDispatch),
                            "FAILED",
                            "stale-redis-replay"
                    ));
                    replayClient.awaitSilence(300, TimeUnit.MILLISECONDS);
                } finally {
                    replayClient.disconnect();
                }

                TaskSnapshot afterReplay = waitForTaskSnapshot(taskId, "TERMINAL", 20, 100L);
                Map<String, Object> message = afterReplay.messages().get(0);
                assertEquals("SUCCESS", message.get("status"));
                assertEquals(STEADY_WORKER_ID, message.get("latestAttemptWorkerId"));
                assertEquals(originalResult, message.get("result"));

                var attemptsAfterReplay = fetchTaskMessageAttempts(taskId, messageId);
                assertEquals(2, attemptsAfterReplay.size(), "stale replay must not create a new attempt");
                assertEquals("EXPIRED", attemptsAfterReplay.get(0).get("status"));
                assertEquals("SUCCEEDED", attemptsAfterReplay.get(1).get("status"));
            } finally {
                steadyClient.disconnect();
            }
        } finally {
            chaosClient.disconnect();
        }
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        assertTrue(awaitCondition(condition, 40, 100L), failureMessage);
    }

    private static final class DisconnectingWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private DisconnectingWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }
    }

    private static final class ReplayWebSocketClient extends SampleWorkerWebSocketClient {
        private ReplayWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        private void awaitSilence(long timeout, TimeUnit unit) throws InterruptedException {
            unit.sleep(timeout);
        }
    }
}

