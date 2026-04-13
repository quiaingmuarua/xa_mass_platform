package com.xa.mass.mock.api;

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

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mock.client.devices-config=mock/test_mock_devices_empty.json",
                "mass.mock.data.devices=mock/test_mock_devices_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiLifecycleGuardsIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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

    private String createTask(String taskName) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("textContent", "guard lifecycle");
        createBody.put("userId", "itest");
        createBody.put("targetList", List.of("target-a", "target-b"));
        createBody.put("batchSize", 1);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));

        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        assertEquals("NEW", task(taskId).get("status"));
        return taskId;
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate free port", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertEquals(Boolean.TRUE, detailResponse.get("success"));
        return (Map<String, Object>) detailResponse.get("task");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(String path, HttpMethod method, Object body) {
        String url = "http://127.0.0.1:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }
}
