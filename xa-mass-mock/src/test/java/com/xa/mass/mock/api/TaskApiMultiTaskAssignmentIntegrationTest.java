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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
@DirtiesContext
class TaskApiMultiTaskAssignmentIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
        registry.add("mock.client.uri", () -> "ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void twoReadyTasksAreAssignedAcrossSeparateDevicesAndBothComplete() throws Exception {
        String firstTaskId = createTask("multi-task-a", "target-a");
        String secondTaskId = createTask("multi-task-b", "target-b");

        assertEquals(Boolean.TRUE, audit(firstTaskId).get("success"));
        assertEquals(Boolean.TRUE, audit(secondTaskId).get("success"));

        TaskSnapshot first = waitForTerminalTask(firstTaskId);
        TaskSnapshot second = waitForTerminalTask(secondTaskId);

        assertTerminalSingleDeviceTask(first);
        assertTerminalSingleDeviceTask(second);

        String firstDeviceId = String.valueOf(first.messages().get(0).get("deviceId"));
        String secondDeviceId = String.valueOf(second.messages().get(0).get("deviceId"));
        assertNotNull(firstDeviceId);
        assertNotNull(secondDeviceId);
        assertEquals(2, Set.of(firstDeviceId, secondDeviceId).size());
    }

    private void assertTerminalSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, ((Number) snapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(1, ((Number) snapshot.task().get("taskExecutedNumber")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertNotNull(message.get("deviceId"));
        assertNotNull(message.get("tokenId"));
        assertNotNull(message.get("batchId"));
    }

    private String createTask(String taskName, String target) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("textContent", "multi task assignment integration");
        createBody.put("userId", "itest");
        createBody.put("targetList", List.of(target));
        createBody.put("batchSize", 1);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));
        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    private Map<String, Object> audit(String taskId) {
        return exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-task",
                HttpMethod.POST, null);
    }

    private TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            Map<String, Object> messagesResponse = exchange(
                    "/status/api/tasks/" + taskId + "/messages?page=1&size=20",
                    HttpMethod.GET,
                    null
            );
            Map<String, Object> task = task(detailResponse);
            List<Map<String, Object>> messages = messages(messagesResponse);
            if ("TERMINAL".equals(task.get("status"))) {
                return new TaskSnapshot(task, messages);
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Task did not reach TERMINAL within timeout");
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
