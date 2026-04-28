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
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers_socket.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.socket.enabled=true",
                "mass.socket.port=0",
                "sample.client.retry-attempts=1",
                "sample.client.retry-delay=1",
                "sample.client.connection-timeout=5"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class SocketTaskApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void createApproveAndCompleteTaskOverAutoStartedSocketMockRuntime() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId("socket-integration-task", "socket integration", List.of("target-a", "target-b"), 1);

        Map<String, Object> beforeAudit = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(beforeAudit);
        Map<String, Object> createdTask = task(beforeAudit);
        assertEquals("NEW", createdTask.get("status"));
        assertEquals("SEALED", createdTask.get("intakeStatus"));
        assertEquals(Boolean.FALSE, createdTask.get("openEnded"));

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=socket-integration",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        TaskSnapshot snapshot = waitForTerminalTask(taskId);

        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals(2, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(2, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, snapshot.messages().size());

        for (Map<String, Object> message : snapshot.messages()) {
            assertEquals("SUCCESS", message.get("status"));
            assertEquals("BUSINESS_SUCCESS", message.get("finalReason"));
            assertNotNull(message.get("latestAttemptWorkerId"));
            assertNotNull(message.get("latestAttemptWorkerContextId"));
            assertNotNull(message.get("latestAttemptBatchId"));
        }
    }
}
