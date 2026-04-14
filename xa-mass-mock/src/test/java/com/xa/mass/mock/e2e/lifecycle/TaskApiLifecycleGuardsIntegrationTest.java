package com.xa.mass.mock.e2e.lifecycle;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mock.client.devices-config=mock/test_mock_devices_empty.json",
                "mass.mock.data.devices=mock/test_mock_devices_empty.json",
                "mass.mock.data.tokens=mock/test_mock_tokens_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiLifecycleGuardsIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void rejectThenApproveTransitionsTaskFromNewToBlockedToReady() {
        String taskId = createTask("guard-reject-approve");

        Map<String, Object> rejectResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=false&comment=reject",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, rejectResponse.get("success"));
        assertEquals("BLOCKED", rejectResponse.get("newStatus"));
        assertEquals("BLOCKED", task(taskId).get("status"));

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));
        assertEquals("READY", approveResponse.get("newStatus"));
        assertEquals("READY", task(taskId).get("status"));
    }

    @Test
    void pauseAndResumeWorkForReadyTaskWhenNoDevicesAreAvailable() {
        String taskId = createTask("guard-pause-resume");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));
        assertEquals("READY", task(taskId).get("status"));

        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, pauseResponse.get("success"));
        assertEquals("PAUSED", task(taskId).get("status"));

        Map<String, Object> resumeResponse = exchange(
                "/status/api/tasks/" + taskId + "/resume",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, resumeResponse.get("success"));
        assertEquals("READY", task(taskId).get("status"));
    }

    @Test
    void deleteGuardRejectsApprovedTaskButAllowsDeletingNewTask() {
        String approvedTaskId = createTask("guard-delete-approved");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + approvedTaskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));
        assertEquals("READY", task(approvedTaskId).get("status"));

        Map<String, Object> rejectDeleteResponse = exchange(
                "/status/api/tasks/" + approvedTaskId,
                HttpMethod.DELETE,
                null
        );
        assertEquals(Boolean.FALSE, rejectDeleteResponse.get("success"));
        assertEquals("READY", task(approvedTaskId).get("status"));

        String newTaskId = createTask("guard-delete-new");
        Map<String, Object> deleteNewResponse = exchange(
                "/status/api/tasks/" + newTaskId,
                HttpMethod.DELETE,
                null
        );
        assertEquals(Boolean.TRUE, deleteNewResponse.get("success"));

        ResponseEntity<Map> missingTaskResponse = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/status/api/tasks/" + newTaskId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class
        );
        assertEquals(404, missingTaskResponse.getStatusCode().value());
    }

    @Test
    void terminateWorksForReadyAndPausedTasks() {
        String readyTaskId = createTask("guard-terminate-ready");
        Map<String, Object> approveReadyResponse = exchange(
                "/status/api/tasks/" + readyTaskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveReadyResponse.get("success"));
        assertEquals("READY", task(readyTaskId).get("status"));

        Map<String, Object> terminateReadyResponse = exchange(
                "/status/api/tasks/" + readyTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, terminateReadyResponse.get("success"));
        assertEquals("TERMINAL", task(readyTaskId).get("status"));

        String pausedTaskId = createTask("guard-terminate-paused");
        Map<String, Object> approvePausedResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approvePausedResponse.get("success"));
        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, pauseResponse.get("success"));
        assertEquals("PAUSED", task(pausedTaskId).get("status"));

        Map<String, Object> terminatePausedResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, terminatePausedResponse.get("success"));
        assertEquals("TERMINAL", task(pausedTaskId).get("status"));
    }

    private String createTask(String taskName) {
        String taskId = createTaskId(taskName, "guard lifecycle", java.util.List.of("target-a", "target-b"), 1);
        assertEquals("NEW", task(taskId).get("status"));
        return taskId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertEquals(Boolean.TRUE, detailResponse.get("success"));
        return (Map<String, Object>) detailResponse.get("task");
    }
}
