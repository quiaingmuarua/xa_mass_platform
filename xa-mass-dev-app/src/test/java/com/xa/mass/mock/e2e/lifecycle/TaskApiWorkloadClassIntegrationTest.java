package com.xa.mass.mock.e2e.lifecycle;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mock.client.retry-attempts=1",
                "mock.client.retry-delay=1",
                "mock.client.connection-timeout=5",
                "mock.client.ping-delay=60",
                "mock.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiWorkloadClassIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void interactiveTaskCompletesOverRealRuntimeAndKeepsExplicitWorkloadClass() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId(
                "interactive-runtime-task",
                "interactive workload smoke",
                List.of("interactive-target-001"),
                1,
                TaskWorkloadClass.INTERACTIVE
        );

        Map<String, Object> auditResponse = audit(taskId, "interactive-workload");
        assertApiOk(auditResponse);

        TaskSnapshot snapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals("INTERACTIVE", snapshot.task().get("workloadClass"));
        assertEquals(1, snapshot.messages().size());
        assertEquals("SUCCESS", snapshot.messages().get(0).get("status"));
        assertNotNull(snapshot.messages().get(0).get("latestAttemptWorkerId"));

        Map<String, Object> detail = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detail);
        assertEquals("INTERACTIVE", task(detail).get("workloadClass"));
    }

    @Test
    void bulkTaskCompletesOverRealRuntimeAndKeepsExplicitWorkloadClass() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId(
                "bulk-runtime-task",
                "bulk workload smoke",
                List.of("bulk-target-001", "bulk-target-002", "bulk-target-003"),
                2,
                TaskWorkloadClass.BULK
        );

        Map<String, Object> auditResponse = audit(taskId, "bulk-workload");
        assertApiOk(auditResponse);

        TaskSnapshot snapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals("BULK", snapshot.task().get("workloadClass"));
        assertEquals(3, snapshot.messages().size());
        for (Map<String, Object> message : snapshot.messages()) {
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("latestAttemptBatchId"));
        }

        Map<String, Object> detail = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detail);
        assertEquals("BULK", task(detail).get("workloadClass"));
    }
}
