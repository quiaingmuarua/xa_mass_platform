package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.sample.client.MockWorkerWebSocketClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TaskApiPauseCompletionIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void pausedRunningTaskClosesToTerminalWhenRealCallbacksArrive() throws Exception {
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

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch);
            assertNotNull(secondDispatch);

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

            Map<String, ManualAckWebSocketClient> clientByWorkerId = Map.of(
                    firstClient.getWorkerId(), firstClient,
                    secondClient.getWorkerId(), secondClient
            );
            Map<String, JsonObject> dispatchByMsgId = new HashMap<>();
            dispatchByMsgId.put(WsFrameTestSupport.messageId(firstDispatch), firstDispatch);
            dispatchByMsgId.put(WsFrameTestSupport.messageId(secondDispatch), secondDispatch);

            for (Map<String, Object> message : pausedSnapshot.messages()) {
                String messageId = String.valueOf(message.get("messageId"));
                String workerId = String.valueOf(message.get("latestAttemptWorkerId"));
                ManualAckWebSocketClient client = clientByWorkerId.get(workerId);
                JsonObject dispatch = dispatchByMsgId.get(messageId);
                assertNotNull(client, "No connected worker client for " + workerId);
                assertNotNull(dispatch, "No captured dispatch frame for message " + messageId);

                client.sendResult(dispatch, "SUCCESS", "paused-complete-" + workerId);
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
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class ManualAckWebSocketClient extends MockWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId) {
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

        private void sendResult(JsonObject dispatchFrame, String status, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(dispatchFrame),
                    WsFrameTestSupport.project(dispatchFrame),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(dispatchFrame),
                    status,
                    detail
            ));
        }
    }
}
