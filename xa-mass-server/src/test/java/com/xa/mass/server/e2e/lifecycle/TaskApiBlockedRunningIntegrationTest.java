package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the RUNNING → BLOCKED → re-approve → RUNNING → TERMINAL path with
 * real worker WebSocket connections.
 *
 * <p>Closing the "BLOCKED state with real in-flight workers completing" coverage gap
 * noted in {@code doc/CURRENT_GAPS.md}.  Prior lifecycle tests covered BLOCKED only
 * from READY (no active workers) via {@link TaskApiLifecycleGuardsIntegrationTest}.
 *
 * <p>Key behaviors verified:
 * <ul>
 *   <li>RUNNING → BLOCKED: in-flight assignments are preserved (messages stay ASSIGNED)</li>
 *   <li>BLOCKED → READY: re-approve restores the dispatch path</li>
 *   <li>Workers complete their original dispatches after unblock → task reaches TERMINAL</li>
 *   <li>Terminal state is ALL_MESSAGES_SUCCEEDED with correct success count</li>
 * </ul>
 */
@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiBlockedRunningIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void blockedRunningTaskCompletesAfterReapproveAndWorkerCallbacks() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-0"),
                "First worker failed to connect"
        );
        ManualAckWebSocketClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-1"),
                "Second worker failed to connect"
        );
        try {
            String taskId = createTaskId("blocked-running",
                    "blocked running e2e",
                    List.of("target-a", "target-b"), 1);

            assertApiOk(approveTask(taskId));

            // Task reaches RUNNING; both messages ASSIGNED to workers.
            TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
            assertEquals(2, runningSnapshot.messages().size());
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertTrue(runningSnapshot.messages().stream()
                    .allMatch(m -> "ASSIGNED".equals(m.get("status"))));

            // Capture both dispatches — workers hold them while we run the block/unblock cycle.
            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "First worker did not receive a dispatch");
            assertNotNull(secondDispatch, "Second worker did not receive a dispatch");

            // Block the RUNNING task — messages must remain ASSIGNED (not revoked).
            assertApiOk(blockTask(taskId));
            TaskSnapshot blockedSnapshot = waitForTaskSnapshot(taskId, "BLOCKED");
            assertEquals("BLOCKED", blockedSnapshot.task().get("status"));
            assertEquals(2, blockedSnapshot.messages().size());
            assertTrue(blockedSnapshot.messages().stream()
                    .allMatch(m -> "ASSIGNED".equals(m.get("status"))),
                    "Blocking a running task must preserve ASSIGNED message state");

            // Re-approve: BLOCKED → READY.
            assertApiOk(approveTask(taskId));
            TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY");
            assertEquals("READY", readySnapshot.task().get("status"));

            // Workers submit SUCCESS from their original (still-valid) dispatches.
            firstClient.sendResult(firstDispatch, "SUCCESS", "blocked-running-complete");
            secondClient.sendResult(secondDispatch, "SUCCESS", "blocked-running-complete");

            // Task must close to TERMINAL with all messages succeeded.
            TaskSnapshot terminalSnapshot = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"),
                    "task with all successful results must close with ALL_MESSAGES_SUCCEEDED");
            assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());

            assertEquals(2, terminalSnapshot.messages().size());
            for (Map<String, Object> msg : terminalSnapshot.messages()) {
                assertEquals("SUCCESS", msg.get("status"));
                assertNotNull(msg.get("latestAttemptWorkerId"));
                assertNotNull(msg.get("latestAttemptWorkerContextId"));
                assertNotNull(msg.get("latestAttemptBatchId"));
            }
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    // ── inner WebSocket client ────────────────────────────────────────────────

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        ManualAckWebSocketClient(URI serverUri, String workerId) {
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
                // Fall through to base handler.
            }
            super.onMessage(message);
        }

        JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        void sendResult(JsonObject taskFrame, String status, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskFrame),
                    WsFrameTestSupport.project(taskFrame),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskFrame),
                    status,
                    detail
            ));
        }
    }
}
