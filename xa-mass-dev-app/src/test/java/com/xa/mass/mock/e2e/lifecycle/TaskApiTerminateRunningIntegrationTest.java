package com.xa.mass.mock.e2e.lifecycle;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mock.client.workers-config=mock/test_mock_workers.json",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiTerminateRunningIntegrationTest {

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
    void terminateStopsTaskAfterAssignmentButBeforeAnyClientResult() throws Exception {
        String taskId = createTask("terminate-running");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));

        TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
        assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(0, ((Number) runningSnapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, runningSnapshot.messages().size());
        assertTrue(runningSnapshot.messages().stream().allMatch(message -> "ASSIGNED".equals(message.get("status"))));

        Map<String, Object> terminateResponse = exchange(
                "/status/api/tasks/" + taskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, terminateResponse.get("success"));

        TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(0, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, terminalSnapshot.messages().size());
        assertTrue(terminalSnapshot.messages().stream().allMatch(message -> "EXPIRED".equals(message.get("status"))));

        Map<String, Object> deleteResponse = exchange(
                "/status/api/tasks/" + taskId,
                HttpMethod.DELETE,
                null
        );
        assertEquals(Boolean.TRUE, deleteResponse.get("success"));

        ResponseEntity<Map> deletedTaskResponse = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/status/api/tasks/" + taskId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Map.class
        );
        assertEquals(404, deletedTaskResponse.getStatusCode().value());
        assertNull(deletedTaskResponse.getBody());
    }

    private String createTask(String taskName) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("routingCode", "us");
        createBody.put("sharedConfig", java.util.Map.of("textContent", "terminate running integration"));
        createBody.put("userId", "itest");
        createBody.put("inputs", List.of(
                Map.of("target", "target-a"),
                Map.of("target", "target-b")
        ));
        createBody.put("batchSize", 1);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));

        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        assertEquals("NEW", task(taskId).get("status"));
        return taskId;
    }

    private TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            Map<String, Object> messagesResponse = exchange(
                    "/status/api/tasks/" + taskId + "/messages?page=1&size=20",
                    HttpMethod.GET,
                    null
            );
            Map<String, Object> task = task(detailResponse);
            List<Map<String, Object>> messages = messages(messagesResponse);
            if (expectedStatus.equals(task.get("status"))) {
                return new TaskSnapshot(task, messages);
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Task did not reach " + expectedStatus + " within timeout");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertEquals(Boolean.TRUE, detailResponse.get("success"));
        return (Map<String, Object>) detailResponse.get("task");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(Map<String, Object> response) {
        return (Map<String, Object>) response.get("task");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("messages");
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

    private record TaskSnapshot(Map<String, Object> task, List<Map<String, Object>> messages) {
    }
}
