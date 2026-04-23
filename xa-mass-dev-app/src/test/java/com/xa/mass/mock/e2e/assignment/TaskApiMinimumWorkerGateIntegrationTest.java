package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TaskApiMinimumWorkerGateIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private TaskManager taskManager;

    @Test
    void readyTaskWaitsUntilMinimumWorkerCountIsSatisfied() throws Exception {
        String firstWorkerId = "min-gate-worker-0";
        registerWorker(firstWorkerId);

        String taskId = createTaskId("min-worker-gate", "minimum worker gate integration", "target-a");
        Task task = taskManager.getTask(taskId);
        task.setMinRequiredWorkerCount(2);
        taskManager.updateTask(task);

        Map<String, Object> auditResponse = audit(taskId, "min-worker-gate");
        assertApiOk(auditResponse);

        TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));
        assertEquals(WorkerContextStatus.IDLE, app.getWorkerContexts(firstWorkerId).get(0).getStatus());

        String secondWorkerId = "min-gate-worker-1";
        registerWorker(secondWorkerId);

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl firstClient = new MassWebSocketClientImpl(uri, firstWorkerId);
        MassWebSocketClientImpl secondClient = new MassWebSocketClientImpl(uri, secondWorkerId);
        try {
            assertClientConnects(firstClient, "first mock client failed to connect");
            assertClientConnects(secondClient, "second mock client failed to connect");

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }
}
