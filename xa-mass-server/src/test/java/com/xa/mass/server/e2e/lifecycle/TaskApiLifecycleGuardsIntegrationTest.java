package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
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
class TaskApiLifecycleGuardsIntegrationTest extends AbstractSampleE2eTest {

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
        assertApiOk(rejectResponse);
        assertEquals("BLOCKED", responseData(rejectResponse).get("newStatus"));
        assertEquals("BLOCKED", task(taskId).get("status"));

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);
        assertEquals("READY", responseData(approveResponse).get("newStatus"));
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
        assertApiOk(approveResponse);
        assertEquals("READY", task(taskId).get("status"));

        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertApiOk(pauseResponse);
        assertEquals("PAUSED", task(taskId).get("status"));

        Map<String, Object> resumeResponse = exchange(
                "/status/api/tasks/" + taskId + "/resume",
                HttpMethod.POST,
                null
        );
        assertApiOk(resumeResponse);
        assertEquals("READY", task(taskId).get("status"));
    }

    @Test
    void readyTaskCanBeBlockedAndApprovedBackToReady() {
        String taskId = createTask("guard-block-approve");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);
        assertEquals("READY", task(taskId).get("status"));

        Map<String, Object> blockResponse = exchange(
                "/status/api/tasks/" + taskId + "/block",
                HttpMethod.POST,
                null
        );
        assertApiOk(blockResponse);
        assertEquals("BLOCKED", task(taskId).get("status"));

        Map<String, Object> reapproveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=reapprove",
                HttpMethod.POST,
                null
        );
        assertApiOk(reapproveResponse);
        assertEquals("READY", task(taskId).get("status"));
    }

    @Test
    void statusEndpointBlocksReadyTaskViaRuntimeBlockPath() {
        String taskId = createTask("guard-status-block");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);
        assertEquals("READY", task(taskId).get("status"));

        Map<String, Object> blockResponse = exchange(
                "/status/api/tasks/" + taskId + "/status?status=BLOCKED",
                HttpMethod.PUT,
                null
        );
        assertApiOk(blockResponse);
        assertEquals("BLOCKED", responseData(blockResponse).get("newStatus"));
        assertEquals("BLOCKED", task(taskId).get("status"));
    }

    @Test
    void createTaskRejectsUnknownFieldsInRequestBody() {
        Map<String, Object> createBody = new java.util.LinkedHashMap<>();
        createBody.put("taskName", "guard-unknown-fields");
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", java.util.Map.of("textContent", "guard lifecycle", "routingCode", "us"));
        createBody.put("userId", "itest");
        createBody.put("inputs", java.util.List.of(java.util.Map.of("target", "target-a")));
        createBody.put("batchSize", 1);
        createBody.put("targetJsonList", java.util.List.of("{\"phone\":\"123\"}"));

        ResponseEntity<String> response = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/status/api/tasks",
                HttpMethod.POST,
                new HttpEntity<>(createBody),
                String.class
        );

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void createTaskAcceptsMaxRuntimeSecondsAsSupportedField() {
        Map<String, Object> createBody = new java.util.LinkedHashMap<>();
        createBody.put("taskName", "guard-max-runtime");
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", java.util.Map.of("textContent", "guard max runtime", "routingCode", "us"));
        createBody.put("userId", "itest");
        createBody.put("inputs", java.util.List.of(java.util.Map.of("target", "target-a")));
        createBody.put("batchSize", 1);
        createBody.put("maxRuntimeSeconds", 120);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertApiOk(createResponse);

        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        assertEquals(120, ((Number) task(taskId).get("maxRuntimeSeconds")).intValue());
    }

    @Test
    void updateTaskRejectsUnsupportedFieldsInRequestBody() {
        String taskId = createTask("guard-update-unknown-fields");

        Map<String, Object> updateBody = new java.util.LinkedHashMap<>();
        updateBody.put("taskName", "guard-update-renamed");
        updateBody.put("inputs", java.util.List.of(java.util.Map.of("target", "target-x")));

        ResponseEntity<String> response = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/status/api/tasks/" + taskId,
                HttpMethod.PUT,
                new HttpEntity<>(updateBody),
                String.class
        );

        assertEquals(400, response.getStatusCode().value());
        assertEquals("NEW", task(taskId).get("status"));
    }

    @Test
    void updateTaskRejectsReadyTaskMutation() {
        String taskId = createTask("guard-update-ready");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);
        assertEquals("READY", task(taskId).get("status"));

        Map<String, Object> updateBody = new java.util.LinkedHashMap<>();
        updateBody.put("taskName", "ready-should-not-update");

        ResponseEntity<String> response = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/status/api/tasks/" + taskId,
                HttpMethod.PUT,
                new HttpEntity<>(updateBody),
                String.class
        );

        assertEquals(400, response.getStatusCode().value());
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
        assertApiOk(approveResponse);
        assertEquals("READY", task(approvedTaskId).get("status"));

        Map<String, Object> rejectDeleteResponse = exchange(
                "/status/api/tasks/" + approvedTaskId,
                HttpMethod.DELETE,
                null
        );
        assertApiError(rejectDeleteResponse, 400);
        assertEquals("READY", task(approvedTaskId).get("status"));

        String newTaskId = createTask("guard-delete-new");
        Map<String, Object> deleteNewResponse = exchange(
                "/status/api/tasks/" + newTaskId,
                HttpMethod.DELETE,
                null
        );
        assertApiOk(deleteNewResponse);

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
        assertApiOk(approveReadyResponse);
        assertEquals("READY", task(readyTaskId).get("status"));

        Map<String, Object> terminateReadyResponse = exchange(
                "/status/api/tasks/" + readyTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertApiOk(terminateReadyResponse);
        assertEquals("TERMINAL", task(readyTaskId).get("status"));

        String pausedTaskId = createTask("guard-terminate-paused");
        Map<String, Object> approvePausedResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(approvePausedResponse);
        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertApiOk(pauseResponse);
        assertEquals("PAUSED", task(pausedTaskId).get("status"));

        Map<String, Object> terminatePausedResponse = exchange(
                "/status/api/tasks/" + pausedTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertApiOk(terminatePausedResponse);
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
        assertApiOk(detailResponse);
        return task(detailResponse);
    }
}
