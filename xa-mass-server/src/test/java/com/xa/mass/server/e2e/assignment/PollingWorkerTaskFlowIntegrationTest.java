package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test verifying the complete polling-worker task lifecycle:
 * create task → approve → poll message → submit result → TERMINAL.
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
class PollingWorkerTaskFlowIntegrationTest extends AbstractSampleE2eTest {

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
        assertFalse(app.isWorkerOnline(workerId), "polling worker registration must not mark worker online");

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            waitUntil(() -> app.isWorkerOnline(workerId), "poll worker connect must surface transport presence online");
            String taskId = createTaskId("polling-e2e-task", "poll integration", "target-poll-001");

            Map<String, Object> auditResponse = approveTask(taskId);
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
            assertNotNull(item.getMessageId());
            assertEquals(workerId, item.getWorkerId());

            boolean submitted = session.submitResult(item, true, "poll-success",
                    Map.of("pollResult", "ok"));
            assertTrue(submitted, "Result submission must succeed");

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
        } finally {
            session.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(workerId), "poll worker disconnect must converge transport presence offline");
    }

    @Test
    void pollingWorkerInboxDoesNotAccumulateAfterDrain() throws Exception {
        String workerId = "poll-worker-drain-001";
        registerPollingWorker(workerId);

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            String taskId = createTaskId("polling-drain-task", "drain test", "target-drain-001");
            approveTask(taskId);

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
            waitForTerminalRuntimeTask(taskId);
        } finally {
            session.disconnect();
        }
    }

    private void registerPollingWorker(String workerId) {
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId("us")
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode("demo.dispatch")
                                .projectCodes(List.of("demoApp"))
                                .build()
                ))
                .transportHint(WorkerTransportHints.POLLING)
                .build());

        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId("ctx-" + workerId)
                .workerId(workerId)
                .routingTags(java.util.Set.of("us"))
                .build());
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
