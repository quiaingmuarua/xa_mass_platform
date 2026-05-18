package com.xa.mass.server.e2e.assignment;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiMultiTaskAssignmentIntegrationTest extends AbstractSampleE2eTest {

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
            assertClientConnects(firstClient, "First manual sample client failed to connect");
            assertClientConnects(secondClient, "Second manual sample client failed to connect");

            String firstTaskId = createTaskId("multi-task-a", "multi task assignment integration", "target-a");
            String secondTaskId = createTaskId("multi-task-b", "multi task assignment integration", "target-b");

            assertApiOk(audit(firstTaskId, "multi-task-a"));
            assertApiOk(audit(secondTaskId, "multi-task-b"));

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);

            assertNotNull(firstDispatch, "First worker should receive one task while it remains in-flight");
            assertNotNull(secondDispatch, "Second worker should receive the other task while the first worker is locked");

            RuntimeTaskSnapshot firstRunning = waitForRuntimeTaskSnapshot(
                    firstTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status")) && snapshot.activeLeases().size() == 1,
                    "RUNNING with one runtime lease",
                    20,
                    250L);
            RuntimeTaskSnapshot secondRunning = waitForRuntimeTaskSnapshot(
                    secondTaskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status")) && snapshot.activeLeases().size() == 1,
                    "RUNNING with one runtime lease",
                    20,
                    250L);

            assertRunningSingleDeviceTask(firstRunning);
            assertRunningSingleDeviceTask(secondRunning);

            String firstWorkerId = firstRunning.activeLeases().getFirst().workerId();
            String secondWorkerId = secondRunning.activeLeases().getFirst().workerId();
            assertEquals(Set.of("it-worker-0", "it-worker-1"), Set.of(firstWorkerId, secondWorkerId));

            firstClient.sendSuccess(firstDispatch, "multi-task-a-ok");
            secondClient.sendSuccess(secondDispatch, "multi-task-b-ok");

            RuntimeTaskSnapshot firstTerminal = waitForTerminalRuntimeTask(firstTaskId);
            RuntimeTaskSnapshot secondTerminal = waitForTerminalRuntimeTask(secondTaskId);

            assertTerminalSingleDeviceTask(firstTerminal);
            assertTerminalSingleDeviceTask(secondTerminal);
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private void assertRunningSingleDeviceTask(RuntimeTaskSnapshot snapshot) {
        assertEquals("RUNNING", snapshot.task().get("status"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, snapshot.activeLeases().size());
        assertNotNull(snapshot.activeLeases().getFirst().workerId());
        assertNotNull(snapshot.activeLeases().getFirst().batchId());
    }

    private void assertTerminalSingleDeviceTask(RuntimeTaskSnapshot snapshot) {
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(1, snapshot.stats().successCount());
        assertEquals(0, snapshot.activeLeases().size());
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
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
                    WsFrameTestSupport.messageId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}

