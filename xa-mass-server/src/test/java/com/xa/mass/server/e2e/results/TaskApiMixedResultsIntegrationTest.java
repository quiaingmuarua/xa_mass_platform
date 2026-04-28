package com.xa.mass.server.e2e.results;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a task with one SUCCESS and one FAILED message closes to TERMINAL
 * with terminalReason=MIXED_MESSAGE_RESULTS and taskSuccessNumber reflecting only successes.
 *
 * <p>This covers the {@code determineTerminalReason()} MIXED branch which is not exercised
 * by any other integration test (all-succeed and all-fail are already covered).
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
class TaskApiMixedResultsIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void taskWithOneSuccessAndOneFailureClosesToTerminalWithMixedReason() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-0"),
                "First mock worker failed to connect"
        );
        ManualAckWebSocketClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-1"),
                "Second mock worker failed to connect"
        );
        try {
            java.util.Map<String, Object> createBody = new java.util.LinkedHashMap<>();
            createBody.put("taskName", "mixed-results");
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "mixed results integration test", "routingCode", "us"));
            createBody.put("userId", "itest");
            createBody.put("inputs", List.of(
                    Map.of("target", "target-a"),
                    Map.of("target", "target-b")
            ));
            createBody.put("batchSize", 1);
            createBody.put("defaultMsgMaxRetryCount", 0);
            Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=mixed-results",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(approveResponse);

            TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, runningSnapshot.messages().size());
            assertTrue(runningSnapshot.messages().stream().allMatch(m -> "ASSIGNED".equals(m.get("status"))));

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch);
            assertNotNull(secondDispatch);

            Map<String, Object> firstMsg = runningSnapshot.messages().get(0);
            Map<String, Object> secondMsg = runningSnapshot.messages().get(1);

            firstClient.sendResult(firstDispatch, "SUCCESS", "mixed-ok");

            secondClient.sendResult(secondDispatch, "FAILED", "mixed-fail");

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("MIXED_MESSAGE_RESULTS", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());

            assertEquals(2, terminalSnapshot.messages().size());
            long successCount = terminalSnapshot.messages().stream()
                    .filter(m -> "SUCCESS".equals(m.get("status"))).count();
            long failedCount = terminalSnapshot.messages().stream()
                    .filter(m -> "FAILED".equals(m.get("status"))).count();
            assertEquals(1, successCount);
            assertEquals(1, failedCount);

            for (Map<String, Object> msg : terminalSnapshot.messages()) {
                assertNotNull(msg.get("latestAttemptWorkerId"));
                assertNotNull(msg.get("latestAttemptWorkerContextId"));
                assertNotNull(msg.get("latestAttemptBatchId"));
            }
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        ManualAckWebSocketClient(URI uri, String workerId) {
            super(uri, workerId);
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
                // Keep waiting.
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

