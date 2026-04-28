package com.xa.mass.server.e2e.audit;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TaskApiStateValidationIntegrationTest extends AbstractMockE2eTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        int websocketPort = findFreePort();
        registerWebSocketPropertiesWithClientUri(registry, websocketPort);
    }

    @Test
    void getTaskExposesValidTerminalStateValidationOverRealRuntime() throws Exception {
        String taskId = createTask("state-validation-terminal");

        Map<String, Object> created = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(created);
        assertEquals(Boolean.TRUE, stateValidation(created).get("valid"));
        assertEquals(Boolean.FALSE, stateValidation(created).get("needsResolution"));
        assertEquals("NEW", stateValidation(created).get("status"));

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=state-validation",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        Map<String, Object> terminal = waitForTaskDetail(taskId, "TERMINAL");
        Map<String, Object> validation = stateValidation(terminal);

        assertEquals(Boolean.TRUE, validation.get("valid"));
        assertEquals(Boolean.FALSE, validation.get("needsResolution"));
        assertEquals("TERMINAL", validation.get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", validation.get("terminalReason"));
        assertEquals(2, ((Number) validation.get("totalMessages")).intValue());
        assertEquals(2, ((Number) validation.get("successMessages")).intValue());
        assertEquals(0, ((Number) validation.get("failedMessages")).intValue());
    }

    @Test
    void getTaskExposesNeedsResolutionWhenTaskIsReopenedAfterMessagesCompleted() throws Exception {
        String taskId = createTask("state-validation-needs-resolution");

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=state-validation-reopen",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = app.getTask(taskId);
        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        assertTrue(updateStoredTask(task));

        Map<String, Object> reopened = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(reopened);

        assertApiOk(reopened);
        assertEquals("RUNNING", task(reopened).get("status"));
        assertEquals(Boolean.TRUE, validation.get("valid"));
        assertEquals(Boolean.TRUE, validation.get("needsResolution"));
        assertEquals("RUNNING", validation.get("status"));
        assertEquals(2, ((Number) validation.get("totalMessages")).intValue());
        assertEquals(2, ((Number) validation.get("successMessages")).intValue());
        assertEquals(0, ((Number) validation.get("failedMessages")).intValue());
    }

    @Test
    void getTaskExposesInvalidStateWhenTerminalReasonIsMissing() throws Exception {
        String taskId = createTask("state-validation-missing-terminal-reason");

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=state-validation-missing-reason",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = app.getTask(taskId);
        task.setTerminalReason(null);
        assertTrue(updateStoredTask(task));

        Map<String, Object> response = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(response);

        assertApiOk(response);
        assertEquals(Boolean.FALSE, validation.get("valid"));
        assertEquals(Boolean.FALSE, validation.get("needsResolution"));
        assertEquals("TERMINAL", validation.get("status"));
        assertEquals(List.of("TERMINAL_REASON_MISSING"), violations(validation));
    }

    @Test
    void getTaskExposesInvalidStateWhenTerminalReasonDoesNotMatchMessageResults() throws Exception {
        String taskId = createTask("state-validation-terminal-reason-mismatch");

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=state-validation-reason-mismatch",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = app.getTask(taskId);
        task.setTerminalReason(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertTrue(updateStoredTask(task));

        Map<String, Object> response = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(response);

        assertApiOk(response);
        assertEquals(Boolean.FALSE, validation.get("valid"));
        assertEquals(Boolean.FALSE, validation.get("needsResolution"));
        assertEquals("TERMINAL", validation.get("status"));
        assertEquals("ALL_MESSAGES_FAILED", validation.get("terminalReason"));
        assertEquals(List.of("TERMINAL_REASON_MISMATCH_ALL_FAILED"), violations(validation));
    }

    private String createTask(String taskName) {
        return createTaskId(taskName, "state validation integration", List.of("target-a", "target-b"), 1);
    }
}
