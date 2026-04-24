package com.xa.mass.mock.e2e.assignment;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MockWorkerWebSocketClient;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.mock.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiMultiTaskAssignmentIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void twoReadyTasksAreAssignedAcrossSeparateDevicesAndBothComplete() throws Exception {
        registerWorker("it-worker-0");
        registerWorker("it-worker-1");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = new ManualAckWebSocketClient(wsUri, "it-worker-0");
        ManualAckWebSocketClient secondClient = new ManualAckWebSocketClient(wsUri, "it-worker-1");

        try {
            assertClientConnects(firstClient, "First manual mock client failed to connect");
            assertClientConnects(secondClient, "Second manual mock client failed to connect");

            String firstTaskId = createTaskId("multi-task-a", "multi task assignment integration", "target-a");
            String secondTaskId = createTaskId("multi-task-b", "multi task assignment integration", "target-b");

            assertApiOk(audit(firstTaskId, "multi-task-a"));
            assertApiOk(audit(secondTaskId, "multi-task-b"));

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);

            assertNotNull(firstDispatch, "First worker should receive one task while it remains in-flight");
            assertNotNull(secondDispatch, "Second worker should receive the other task while the first worker is locked");

            TaskSnapshot firstRunning = waitForTaskSnapshot(firstTaskId, "RUNNING");
            TaskSnapshot secondRunning = waitForTaskSnapshot(secondTaskId, "RUNNING");

            assertRunningSingleDeviceTask(firstRunning);
            assertRunningSingleDeviceTask(secondRunning);

            String firstWorkerId = String.valueOf(firstRunning.messages().get(0).get("latestAttemptWorkerId"));
            String secondWorkerId = String.valueOf(secondRunning.messages().get(0).get("latestAttemptWorkerId"));
            assertEquals(Set.of("it-worker-0", "it-worker-1"), Set.of(firstWorkerId, secondWorkerId));

            firstClient.sendSuccess(firstDispatch, "multi-task-a-ok");
            secondClient.sendSuccess(secondDispatch, "multi-task-b-ok");

            TaskSnapshot firstTerminal = waitForTerminalTask(firstTaskId);
            TaskSnapshot secondTerminal = waitForTerminalTask(secondTaskId);

            assertTerminalSingleDeviceTask(firstTerminal);
            assertTerminalSingleDeviceTask(secondTerminal);
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private void assertRunningSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("RUNNING", snapshot.task().get("status"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertNotNull(message.get("latestAttemptWorkerId"));
        assertNotNull(message.get("latestAttemptWorkerContextId"));
        assertNotNull(message.get("latestAttemptBatchId"));
    }

    private void assertTerminalSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertNotNull(message.get("latestAttemptWorkerId"));
        assertNotNull(message.get("latestAttemptWorkerContextId"));
        assertNotNull(message.get("latestAttemptBatchId"));
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
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
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.msgId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}
