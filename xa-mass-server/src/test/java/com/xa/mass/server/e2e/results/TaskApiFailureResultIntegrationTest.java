package com.xa.mass.server.e2e.results;

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
                "sample.client.task-result-status=FAILED",
                "mass.mock.data.workers=mock/test_mock_workers.json",
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
public class TaskApiFailureResultIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void createApproveAndFailTaskOverRealMockRuntime() throws Exception {
        assertMinOnlineWorkers(2);
        String taskId = createTaskId("integration-task-failure", "integration failure smoke", List.of("target-a", "target-b"), 1);

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        RuntimeTaskSnapshot snapshot = waitForTerminalRuntimeTask(taskId);

        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_FAILED", snapshot.task().get("terminalReason"));
        assertEquals(2, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(0, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, snapshot.stats().totalCount());
        assertEquals(0, snapshot.stats().successCount());
        assertEquals(2, snapshot.stats().failedCount());
        assertEquals(2, snapshot.stats().finalCount());
    }
}
