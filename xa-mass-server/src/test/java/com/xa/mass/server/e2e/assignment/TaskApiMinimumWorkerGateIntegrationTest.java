package com.xa.mass.server.e2e.assignment;

import com.xa.mass.base.model.Task;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiMinimumWorkerGateIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void readyTaskWaitsUntilMinimumWorkerCountIsSatisfied() throws Exception {
        String firstWorkerId = "min-gate-worker-0";
        registerWorker(firstWorkerId);
        assertFalse(app.isWorkerOnline(firstWorkerId), "worker registration must not mark first worker online");

        String taskId = createTaskId("min-worker-gate", "minimum worker gate integration", "target-a");
        Task task = taskStorage.getTask(taskId).orElseThrow();
        task.setMinRequiredWorkerCount(2);
        assertTrue(updateStoredTask(task));

        Map<String, Object> auditResponse = audit(taskId, "min-worker-gate");
        assertApiOk(auditResponse);

        TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));
        assertEquals("IDLE", app.getWorkerContexts(firstWorkerId).get(0).getStatus());

        String secondWorkerId = "min-gate-worker-1";
        registerWorker(secondWorkerId);
        assertFalse(app.isWorkerOnline(secondWorkerId), "worker registration must not mark second worker online");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient firstClient = new SampleWorkerWebSocketClient(uri, firstWorkerId);
        SampleWorkerWebSocketClient secondClient = new SampleWorkerWebSocketClient(uri, secondWorkerId);
        try {
            assertClientConnects(firstClient, "first sample client failed to connect");
            waitUntil(() -> app.isWorkerOnline(firstWorkerId), "first worker connect must mark worker online");

            TaskSnapshot stillReadyWithSingleOnlineWorker = waitForTaskSnapshot(taskId, "READY", 8, 250L);
            assertEquals(0, ((Number) stillReadyWithSingleOnlineWorker.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals("INIT", stillReadyWithSingleOnlineWorker.messages().get(0).get("status"));
            assertEquals(null, stillReadyWithSingleOnlineWorker.messages().get(0).get("latestAttemptWorkerId"));

            assertClientConnects(secondClient, "second sample client failed to connect");
            waitUntil(() -> app.isWorkerOnline(secondWorkerId), "second worker connect must mark worker online");

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(firstWorkerId), "first worker disconnect must mark worker offline");
        waitUntil(() -> !app.isWorkerOnline(secondWorkerId), "second worker disconnect must mark worker offline");
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}

