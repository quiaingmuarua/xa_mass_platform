package com.xa.mass.mock.e2e.lifecycle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MockWorkerWebSocketClient;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.mock.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiPauseCompletionIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void pausedRunningTaskClosesToTerminalWhenRealCallbacksArrive() throws Exception {
        String taskId = createTaskId("pause-completion", "pause completion integration", List.of("target-a", "target-b"), 1);

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);

        TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
        assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(2, runningSnapshot.messages().size());
        assertTrue(runningSnapshot.messages().stream().allMatch(message -> "ASSIGNED".equals(message.get("status"))));

        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertApiOk(pauseResponse);

        TaskSnapshot pausedSnapshot = waitForTaskSnapshot(taskId, "PAUSED");
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        assertEquals(2, pausedSnapshot.messages().size());
        assertTrue(pausedSnapshot.messages().stream().allMatch(message -> "ASSIGNED".equals(message.get("status"))));

        for (Map<String, Object> message : pausedSnapshot.messages()) {
            String msgId = String.valueOf(message.get("msgId"));
            String workerId = String.valueOf(message.get("latestAttemptWorkerId"));
            AckSnapshot ack = submitTaskResult(taskId, msgId, workerId, "SUCCESS", "paused-complete-" + workerId);
            assertEquals(200, ack.code());
            assertEquals("task result processed", ack.message());
        }

        TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, terminalSnapshot.messages().size());
        for (Map<String, Object> message : terminalSnapshot.messages()) {
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("latestAttemptWorkerId"));
            assertNotNull(message.get("latestAttemptWorkerContextId"));
            assertNotNull(message.get("latestAttemptBatchId"));
        }
    }

    private AckSnapshot submitTaskResult(String taskId, String msgId, String workerId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, workerId, msgId);
        try {
            assertClientConnects(client, "Replay WebSocket client failed to connect");
            client.sendMessage(WsFrameTestSupport.buildTaskResult(msgId, "demoApp", workerId, taskId, status, detail));
            assertTrue(client.awaitAck(3, TimeUnit.SECONDS), "Timed out waiting for gateway ack");
            return client.ackSnapshot();
        } finally {
            client.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detailResponse);
        return task(detailResponse);
    }

    private record AckSnapshot(int code, String message) {
    }

    private static final class ReplayWebSocketClient extends MockWorkerWebSocketClient {
        private final String expectedMsgId;
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private volatile AckSnapshot ackSnapshot;

        private ReplayWebSocketClient(URI serverUri, String workerId, String expectedMsgId) {
            super(serverUri, workerId);
            this.expectedMsgId = expectedMsgId;
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null
                        && WsFrameTestSupport.isResponse(frame)
                        && WsFrameTestSupport.isTask(frame)
                        && expectedMsgId.equals(WsFrameTestSupport.msgId(frame))) {
                    ackSnapshot = new AckSnapshot(
                            WsFrameTestSupport.ackCode(frame),
                            WsFrameTestSupport.ackMessage(frame)
                    );
                    ackLatch.countDown();
                }
            } catch (Exception ignored) {
                // Keep waiting for the expected task ack frame.
            }
            super.onMessage(message);
        }

        private boolean awaitAck(long timeout, TimeUnit unit) throws InterruptedException {
            return ackLatch.await(timeout, unit);
        }

        private AckSnapshot ackSnapshot() {
            return ackSnapshot;
        }
    }
}
