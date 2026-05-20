package com.xa.mass.server.e2e.audit;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("secondary-proof")
public class TaskApiStateValidationIntegrationTest extends AbstractSampleE2eTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        int websocketPort = findFreePort();
        registerWebSocketPropertiesWithClientUri(registry, websocketPort);
    }

    @Test
    void getTaskExposesValidTerminalStateValidationOverRealRuntime() throws Exception {
        String taskId = createSeededTaskShell("state-validation-terminal");

        Map<String, Object> created = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(created);
        TaskStateValidationResult createdValidation = validateTaskState(taskId);
        assertTrue(createdValidation.isValid());
        assertFalse(createdValidation.isNeedsResolution());
        assertEquals("NEW", createdValidation.getStatus().name());

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        Map<String, Object> terminal = waitForTaskDetail(taskId, "TERMINAL");
        TaskStateValidationResult validation = validateTaskState(taskId);

        assertTrue(validation.isValid());
        assertFalse(validation.isNeedsResolution());
        assertEquals("TERMINAL", validation.getStatus().name());
        assertEquals("ALL_MESSAGES_SUCCEEDED", validation.getTerminalReason().name());
        assertEquals(2, validation.getTotalMessages());
        assertEquals(2, validation.getSuccessMessages());
        assertEquals(0, validation.getFailedMessages());
    }

    @Test
    void getTaskExposesNeedsResolutionWhenTaskIsReopenedAfterMessagesCompleted() throws Exception {
        String taskId = createSeededTaskShell("state-validation-needs-resolution");

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskStorage.getTask(taskId).orElseThrow();
        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        assertTrue(updateStoredTask(task));

        Map<String, Object> reopened = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        TaskStateValidationResult validation = validateTaskState(taskId);

        assertApiOk(reopened);
        assertEquals("RUNNING", task(reopened).get("status"));
        assertTrue(validation.isValid());
        assertTrue(validation.isNeedsResolution());
        assertEquals("RUNNING", validation.getStatus().name());
        assertEquals(2, validation.getTotalMessages());
        assertEquals(2, validation.getSuccessMessages());
        assertEquals(0, validation.getFailedMessages());
    }

    @Test
    void getTaskExposesInvalidStateWhenTerminalReasonIsMissing() throws Exception {
        String taskId = createSeededTaskShell("state-validation-missing-terminal-reason");

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskStorage.getTask(taskId).orElseThrow();
        task.setTerminalReason(null);
        assertTrue(updateStoredTask(task));

        Map<String, Object> response = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        TaskStateValidationResult validation = validateTaskState(taskId);

        assertApiOk(response);
        assertFalse(validation.isValid());
        assertFalse(validation.isNeedsResolution());
        assertEquals("TERMINAL", validation.getStatus().name());
        assertEquals(List.of("TERMINAL_REASON_MISSING"), violations(validation));
    }

    @Test
    void getTaskExposesInvalidStateWhenTerminalReasonDoesNotMatchMessageResults() throws Exception {
        String taskId = createSeededTaskShell("state-validation-terminal-reason-mismatch");

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskStorage.getTask(taskId).orElseThrow();
        task.setTerminalReason(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertTrue(updateStoredTask(task));

        Map<String, Object> response = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        TaskStateValidationResult validation = validateTaskState(taskId);

        assertApiOk(response);
        assertFalse(validation.isValid());
        assertFalse(validation.isNeedsResolution());
        assertEquals("TERMINAL", validation.getStatus().name());
        assertEquals("ALL_MESSAGES_FAILED", validation.getTerminalReason().name());
        assertEquals(List.of("TERMINAL_REASON_MISMATCH_ALL_FAILED"), violations(validation));
    }

    private String createSeededTaskShell(String taskName) {
        return createTaskId(taskName, "state validation integration", List.of("target-a", "target-b"), 1);
    }
}
