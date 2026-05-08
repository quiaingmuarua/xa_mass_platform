package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the "resume short-circuit" corner cases for PAUSED tasks:
 *
 * <ol>
 *   <li><b>completedWhilePaused_thenResumeReturnsConflict</b> — workers complete their
 *       dispatches while the task is PAUSED; the engine auto-closes to TERMINAL via the
 *       callback path; a subsequent {@code resumeTask} call on the TERMINAL task returns
 *       409 conflict with a meaningful message (not a 500 server error).</li>
 *   <li><b>resumeOnRunningTaskReturnsConflict</b> — calling resume on a RUNNING task
 *       (not PAUSED) is cleanly rejected with 409.</li>
 * </ol>
 *
 * <p>Complements {@link TaskApiPauseCompletionIntegrationTest} which verifies that
 * callbacks arriving while PAUSED close the task to TERMINAL; this test additionally
 * asserts the HTTP-level graceful rejection when the caller tries to resume an
 * already-closed task.
 *
 * <p>Closes the "Resume short-circuit where a paused task is already complete
 * underneath" coverage gap in {@code doc/CURRENT_GAPS.md}.
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
class TaskApiResumeShortCircuitIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    /**
     * Task completes while PAUSED (callbacks arrive from in-flight workers after the
     * pause command).  The engine closes it PAUSED → TERMINAL via the result callback
     * path.  A subsequent resumeTask call on the TERMINAL task must return HTTP 409
     * conflict (not 500), and the task must remain TERMINAL.
     */
    @Test
    void completedWhilePaused_thenResumeReturnsConflict() throws Exception {
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
            String taskId = createTaskId("resume-short-circuit",
                    "resume short-circuit e2e",
                    List.of("target-a", "target-b"), 1);

            Map<String, Object> approveResponse = exchange(
                    "/api/v1/tasks/" + taskId + ":approve", HttpMethod.POST, null);
            assertApiOk(approveResponse);

            // Task reaches RUNNING with both workers assigned.
            TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
            assertEquals(2, runningSnapshot.messages().size());
            assertTrue(runningSnapshot.messages().stream()
                    .allMatch(m -> "ASSIGNED".equals(m.get("status"))));

            // Capture dispatches before pausing.
            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "First worker did not receive a dispatch");
            assertNotNull(secondDispatch, "Second worker did not receive a dispatch");

            // Pause the running task — workers still hold their dispatches.
            assertApiOk(pauseTask(taskId));
            waitForTaskSnapshot(taskId, "PAUSED");

            // Build worker → dispatch index for correlation.
            Map<String, ManualAckWebSocketClient> clientMap = Map.of(
                    firstClient.getWorkerId(), firstClient,
                    secondClient.getWorkerId(), secondClient
            );
            Map<String, JsonObject> dispatchMap = new HashMap<>();
            dispatchMap.put(WsFrameTestSupport.messageId(firstDispatch), firstDispatch);
            dispatchMap.put(WsFrameTestSupport.messageId(secondDispatch), secondDispatch);

            // Workers submit SUCCESS while PAUSED — engine auto-closes PAUSED → TERMINAL.
            TaskSnapshot pausedSnapshot = waitForTaskSnapshot(taskId, "PAUSED");
            for (Map<String, Object> msg : pausedSnapshot.messages()) {
                String msgId = String.valueOf(msg.get("messageId"));
                String workerId = String.valueOf(msg.get("latestAttemptWorkerId"));
                ManualAckWebSocketClient client = clientMap.get(workerId);
                JsonObject dispatch = dispatchMap.get(msgId);
                assertNotNull(client, "No worker client for " + workerId);
                assertNotNull(dispatch, "No captured dispatch for message " + msgId);
                client.sendResult(dispatch, "SUCCESS", "resume-short-circuit-" + workerId);
            }

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));

            // Now try to resume the TERMINAL task — must return 409, not 500.
            Map<String, Object> staleResumeResponse = resumeTask(taskId);
            assertApiError(staleResumeResponse, 409);
            assertNotNull(apiMsg(staleResumeResponse),
                    "Conflict response must include a message");

            // Task must remain TERMINAL — resume must not have mutated state.
            TaskSnapshot afterResumeSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
            assertEquals("TERMINAL", afterResumeSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", afterResumeSnapshot.task().get("terminalReason"));
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    /**
     * Calling resumeTask on a RUNNING task (not PAUSED) must return 409 conflict.
     * The task must remain RUNNING after the rejected resume call.
     */
    @Test
    void resumeOnRunningTaskReturnsConflict() throws Exception {
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
            String taskId = createTaskId("resume-running-reject",
                    "resume running reject e2e",
                    List.of("target-a", "target-b"), 1);

            assertApiOk(approveTask(taskId));

            // Wait until both messages are ASSIGNED — task is RUNNING.
            TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
            assertEquals("RUNNING", runningSnapshot.task().get("status"));

            // Both workers must have received their dispatches.
            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "First worker did not receive a dispatch");
            assertNotNull(secondDispatch, "Second worker did not receive a dispatch");

            // Resume on a RUNNING task must be rejected — RUNNING is not a resumable state.
            Map<String, Object> rejectedResumeResponse = resumeTask(taskId);
            assertApiError(rejectedResumeResponse, 409);
            assertNotNull(apiMsg(rejectedResumeResponse),
                    "Conflict response must include a message");

            // Task must remain RUNNING.
            assertEquals("RUNNING",
                    waitForTaskSnapshot(taskId, "RUNNING").task().get("status"));

            // Clean up: workers submit SUCCESS to let the task terminate naturally.
            firstClient.sendResult(firstDispatch, "SUCCESS", "cleanup");
            secondClient.sendResult(secondDispatch, "SUCCESS", "cleanup");
            waitForTaskSnapshot(taskId, "TERMINAL");
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
