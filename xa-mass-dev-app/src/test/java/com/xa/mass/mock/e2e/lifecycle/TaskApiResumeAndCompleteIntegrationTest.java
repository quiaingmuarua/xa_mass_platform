package com.xa.mass.mock.e2e.lifecycle;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the PAUSED → READY → RUNNING → TERMINAL path via a real resume call
 * followed by worker connection and mock callback.
 *
 * <p>Specifically:
 * <ol>
 *   <li>Task is approved while no workers are available -> stays READY, peakAssignedWorkerCount=0.</li>
 *   <li>Task is paused (READY → PAUSED).</li>
 *   <li>A matching worker is registered and a mock client connects.</li>
 *   <li>Task is resumed (PAUSED → READY); {@code notifyTaskReady} kicks the assign worker.</li>
 *   <li>TaskAssignWorker assigns the task to the new worker (READY → RUNNING).</li>
 *   <li>Mock client auto-sends a SUCCESS callback → task closes to TERMINAL.</li>
 * </ol>
 *
 * <p>This path is distinct from {@link TaskApiPauseCompletionIntegrationTest}, which covers
 * callbacks arriving <em>while paused</em> (no resume needed).
 */
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
class TaskApiResumeAndCompleteIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void resumedPausedTaskCompletesAfterWorkerConnectsAndSendsCallback() throws Exception {
        // 1. Create and approve a task — no workers online yet.
        String taskId = createTaskId("resume-and-complete", "resume and complete integration test", "target-a");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=resume-and-complete",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);

        // 2. Task reaches READY but stays there (no matching worker).
        TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, readySnapshot.messages().size());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));

        // 3. Pause the READY task.
        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertApiOk(pauseResponse);

        TaskSnapshot pausedSnapshot = waitForTaskSnapshot(taskId, "PAUSED", 4, 500L);
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        // Message is still INIT — no worker was ever assigned.
        assertEquals("INIT", pausedSnapshot.messages().get(0).get("status"));

        // 4. Register a matching worker and connect a mock client.
        String workerId = "resume-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, workerId);
        try {
            assertClientConnects(client, "Mock client failed to connect");

            // 5. Resume the task: PAUSED → READY → assign worker picks it up → RUNNING → TERMINAL.
            Map<String, Object> resumeResponse = exchange(
                    "/status/api/tasks/" + taskId + "/resume",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(resumeResponse);

            // 6. Task should proceed all the way to TERMINAL via the new worker.
            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());

            assertEquals(1, terminalSnapshot.messages().size());
            Map<String, Object> msg = terminalSnapshot.messages().get(0);
            assertEquals("SUCCESS", msg.get("status"));
            assertEquals(workerId, msg.get("latestAttemptWorkerId"));
            assertNotNull(msg.get("latestAttemptWorkerContextId"));
            assertNotNull(msg.get("latestAttemptBatchId"));
        } finally {
            client.disconnect();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void registerWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        workerManager.addWorker(worker);

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("worker-context-" + workerId);
        workerContext.setWorkerId(workerId);
        workerContext.setRoutingTags(java.util.Set.of("us"));
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerManager.addWorkerContext(workerContext);
    }

}
