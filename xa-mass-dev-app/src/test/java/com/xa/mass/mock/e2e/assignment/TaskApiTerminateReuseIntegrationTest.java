package com.xa.mass.mock.e2e.assignment;

import com.google.gson.Gson;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.net.URI;
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
class TaskApiTerminateReuseIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void terminatedTaskReleasesSingleDeviceForNextTask() throws Exception {
        String workerId = "terminate-reuse-worker-0";
        registerWorker(workerId);
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualHoldWebSocketClient client = new ManualHoldWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "terminate reuse worker client failed to connect");

            String firstTaskId = createTaskId("terminate-reuse-first", "terminate reuse first", "target-a");
            Map<String, Object> firstApprove = audit(firstTaskId, "terminate-reuse-1");
            assertApiOk(firstApprove);

            MassMessage firstDispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "first task should be dispatched before termination");
            TaskSnapshot firstRunning = waitForTaskSnapshot(firstTaskId, "RUNNING", 20, 500L);
            assertEquals(workerId, firstRunning.messages().get(0).get("latestAttemptWorkerId"));

            Map<String, Object> firstTerminate = exchange(
                    "/status/api/tasks/" + firstTaskId + "/terminate",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(firstTerminate);

            TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
            assertEquals("EXPIRED", firstTerminal.messages().get(0).get("status"));
            assertEquals(WorkerContextStatus.IDLE, app.getWorkerContexts(workerId).get(0).getStatus());

            String secondTaskId = createTaskId("terminate-reuse-second", "terminate reuse second", "target-b");
            Map<String, Object> secondApprove = audit(secondTaskId, "terminate-reuse-2");
            assertApiOk(secondApprove);

            MassMessage secondDispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(secondDispatch, "second task should be dispatched after the first task is terminated");
            TaskSnapshot secondRunning = waitForTaskSnapshot(secondTaskId, "RUNNING", 20, 500L);
            assertEquals(workerId, secondRunning.messages().get(0).get("latestAttemptWorkerId"));

            Map<String, Object> secondTerminate = exchange(
                    "/status/api/tasks/" + secondTaskId + "/terminate",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(secondTerminate);
            waitForTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
            assertEquals(WorkerContextStatus.IDLE, app.getWorkerContexts(workerId).get(0).getStatus());
        } finally {
            client.disconnect();
        }
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }

    private static final class ManualHoldWebSocketClient extends MassWebSocketClientImpl {
        private final BlockingQueue<MassMessage> taskQueue = new LinkedBlockingQueue<>();

        private ManualHoldWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                MassMessage massMessage = GSON.fromJson(message, MassMessage.class);
                if (massMessage != null && massMessage.getMsgType() == MessageType.TASK && !massMessage.isResponse()) {
                    taskQueue.offer(massMessage);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to base handling for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private MassMessage awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }
    }
}
