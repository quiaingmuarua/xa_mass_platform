package com.xa.mass.mock.api;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mock.client.devices-config=mock/test_mock_devices.json",
                "mass.mock.data.devices=mock/test_mock_devices.json",
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TaskApiStateValidationIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        int websocketPort = findFreePort();
        registry.add("mass.websocket.port", () -> websocketPort);
        registry.add("mock.client.uri", () -> "ws://127.0.0.1:" + websocketPort + "/ws");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TaskManager taskManager;

    @Test
    void getTaskExposesValidTerminalStateValidationOverRealRuntime() throws Exception {
        String taskId = createTask("state-validation-terminal");

        Map<String, Object> created = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertEquals(Boolean.TRUE, created.get("success"));
        assertEquals(Boolean.TRUE, stateValidation(created).get("valid"));
        assertEquals(Boolean.FALSE, stateValidation(created).get("needsResolution"));
        assertEquals("NEW", stateValidation(created).get("status"));

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=state-validation",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

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
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskManager.getTask(taskId);
        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        assertTrue(taskManager.updateTask(task));

        Map<String, Object> reopened = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(reopened);

        assertEquals(Boolean.TRUE, reopened.get("success"));
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
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskManager.getTask(taskId);
        task.setTerminalReason(null);
        assertTrue(taskManager.updateTask(task));

        Map<String, Object> response = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(response);

        assertEquals(Boolean.TRUE, response.get("success"));
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
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        waitForTaskDetail(taskId, "TERMINAL");

        Task task = taskManager.getTask(taskId);
        task.setTerminalReason(TaskTerminalReason.ALL_MESSAGES_FAILED);
        assertTrue(taskManager.updateTask(task));

        Map<String, Object> response = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> validation = stateValidation(response);

        assertEquals(Boolean.TRUE, response.get("success"));
        assertEquals(Boolean.FALSE, validation.get("valid"));
        assertEquals(Boolean.FALSE, validation.get("needsResolution"));
        assertEquals("TERMINAL", validation.get("status"));
        assertEquals("ALL_MESSAGES_FAILED", validation.get("terminalReason"));
        assertEquals(List.of("TERMINAL_REASON_MISMATCH_ALL_FAILED"), violations(validation));
    }

    private String createTask(String taskName) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("textContent", "state validation integration");
        createBody.put("userId", "itest");
        createBody.put("targetList", List.of("target-a", "target-b"));
        createBody.put("batchSize", 1);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));

        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    private Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            if (expectedStatus.equals(task(detailResponse).get("status"))) {
                return detailResponse;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Task did not reach " + expectedStatus + " within timeout");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(Map<String, Object> response) {
        return (Map<String, Object>) response.get("task");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stateValidation(Map<String, Object> response) {
        return (Map<String, Object>) response.get("stateValidation");
    }

    @SuppressWarnings("unchecked")
    private List<String> violations(Map<String, Object> validation) {
        return (List<String>) validation.get("violations");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(String path, HttpMethod method, Object body) {
        String url = "http://127.0.0.1:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate free port", e);
        }
    }
}
