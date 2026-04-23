package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.worker.PollingWorkerAdapter;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E test verifying the complete polling-worker task lifecycle:
 * create task → approve → poll message → submit result → TERMINAL.
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
class PollingWorkerTaskFlowIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void pollingWorkerCompletesTaskEndToEnd() throws Exception {
        String workerId = "poll-worker-e2e-001";
        registerPollingWorker(workerId);

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            String taskId = createTaskId("polling-e2e-task", "poll integration", "target-poll-001");

            Map<String, Object> auditResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=polling-e2e",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(auditResponse);

            // Poll until at least one message appears.
            List<TaskDispatchItem> items = List.of();
            for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
                items = session.poll(10);
                if (items.isEmpty()) {
                    Thread.sleep(250L);
                }
            }
            assertFalse(items.isEmpty(), "Expected at least one dispatched item via polling");

            TaskDispatchItem item = items.get(0);
            assertEquals(taskId, item.getTaskId());
            assertNotNull(item.getMsgId());
            assertEquals(workerId, item.getWorkerId());

            boolean submitted = session.submitResult(item, true, "poll-success",
                    Map.of("pollResult", "ok"));
            assertTrue(submitted, "Result submission must succeed");

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.messages().size());
            assertEquals("SUCCESS", terminal.messages().get(0).get("status"));
            assertEquals(workerId, terminal.messages().get(0).get("latestAttemptWorkerId"));
        } finally {
            session.disconnect();
        }
    }

    @Test
    void pollingWorkerInboxDoesNotAccumulateAfterDrain() throws Exception {
        String workerId = "poll-worker-drain-001";
        registerPollingWorker(workerId);

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            String taskId = createTaskId("polling-drain-task", "drain test", "target-drain-001");
            exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=drain", HttpMethod.POST, null);

            // Drain until we receive the message.
            List<TaskDispatchItem> items = List.of();
            for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
                items = session.poll(10);
                if (items.isEmpty()) {
                    Thread.sleep(250L);
                }
            }
            assertFalse(items.isEmpty(), "Expected a dispatched item");

            // After draining, subsequent poll must return empty (inbox cleaned up).
            List<TaskDispatchItem> afterDrain = session.poll(10);
            assertTrue(afterDrain.isEmpty(), "Inbox should be empty after draining all items");

            // Submit result to let task complete cleanly.
            session.submitResult(items.get(0), true, "drain-ok", Map.of());
            waitForTerminalTask(taskId);
        } finally {
            session.disconnect();
        }
    }

    private void registerPollingWorker(String workerId) {
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId("us")
                .supportedProjects(List.of("demoApp"))
                .transportHint(PollingWorkerAdapter.PROTOCOL)
                .build());

        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId("ctx-" + workerId)
                .workerId(workerId)
                .routingTags(java.util.Set.of("us"))
                .build());
    }
}
