package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "sample.client.retry-attempts=1",
                "sample.client.retry-delay=1",
                "sample.client.connection-timeout=5",
                "sample.client.ping-delay=60",
                "sample.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void createApproveAndCompleteTaskOverRealMockRuntime() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId("integration-task", "integration smoke", List.of("target-a", "target-b"), 1);

        Map<String, Object> beforeAudit = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(beforeAudit);
        Map<String, Object> createdTask = task(beforeAudit);
        assertEquals("NEW", createdTask.get("status"));
        assertEquals("SEALED", createdTask.get("intakeStatus"));
        assertEquals(false, createdTask.containsKey("openEnded"));

        Map<String, Object> auditResponse = exchange(
                "/api/v1/tasks/" + taskId + ":approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        RuntimeTaskSnapshot snapshot = waitForTerminalRuntimeTask(taskId);

        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(2, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(2, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, snapshot.stats().totalCount());
        assertEquals(2, snapshot.stats().successCount());
        assertEquals(0, snapshot.stats().failedCount());
        assertEquals(0, snapshot.stats().expiredCount());
    }

    @Test
    void interactiveWorkloadClassPreservedThroughTerminal() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId(
                "interactive-workload-task",
                "interactive workload smoke",
                List.of("interactive-target-001"),
                1,
                "INTERACTIVE"
        );
        assertApiOk(audit(taskId, "interactive-workload"));

        RuntimeTaskSnapshot snapshot = waitForTerminalRuntimeTask(taskId);
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals("INTERACTIVE", snapshot.task().get("workloadClass"));
        assertEquals(1, snapshot.stats().successCount());

        Map<String, Object> detail = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detail);
        assertEquals("INTERACTIVE", task(detail).get("workloadClass"));
    }

    @Test
    void bulkWorkloadClassPreservedThroughTerminal() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId(
                "bulk-workload-task",
                "bulk workload smoke",
                List.of("bulk-target-001", "bulk-target-002", "bulk-target-003"),
                2,
                "BULK"
        );
        assertApiOk(audit(taskId, "bulk-workload"));

        RuntimeTaskSnapshot snapshot = waitForTerminalRuntimeTask(taskId);
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals("BULK", snapshot.task().get("workloadClass"));
        assertEquals(3, snapshot.stats().totalCount());
        assertEquals(3, snapshot.stats().successCount());

        Map<String, Object> detail = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detail);
        assertEquals("BULK", task(detail).get("workloadClass"));
    }
}
