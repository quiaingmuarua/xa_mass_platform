package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the PAUSED 鈫?READY 鈫?RUNNING 鈫?TERMINAL path via a real resume call
 * followed by worker connection and mock callback.
 *
 * <p>Specifically:
 * <ol>
 *   <li>Task is approved while no workers are available -> stays READY, peakAssignedWorkerCount=0.</li>
 *   <li>Task is paused (READY 鈫?PAUSED).</li>
 *   <li>A matching worker is registered and a sample client connects.</li>
 *   <li>Task is resumed (PAUSED 鈫?READY); {@code notifyTaskReady} kicks the assign worker.</li>
 *   <li>TaskAssignWorker assigns the task to the new worker (READY 鈫?RUNNING).</li>
 *   <li>Sample client auto-sends a SUCCESS callback 鈫?task closes to TERMINAL.</li>
 * </ol>
 *
 * <p>This path is distinct from {@link TaskApiPauseCompletionIntegrationTest}, which covers
 * callbacks arriving <em>while paused</em> (no resume needed).
 */
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
class TaskApiResumeAndCompleteIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void resumedPausedTaskCompletesAfterWorkerConnectsAndSendsCallback() throws Exception {
        // 1. Create and approve a task 鈥?no workers online yet.
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
        // Message is still INIT 鈥?no worker was ever assigned.
        assertEquals("INIT", pausedSnapshot.messages().get(0).get("status"));

        // 4. Register a matching worker and connect a sample client.
        String workerId = "resume-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "Sample client failed to connect");

            // 5. Resume the task: PAUSED 鈫?READY 鈫?assign worker picks it up 鈫?RUNNING 鈫?TERMINAL.
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

    // 鈹€鈹€鈹€ helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }

}

