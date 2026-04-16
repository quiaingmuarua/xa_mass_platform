package com.xa.mass.mock.e2e.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public abstract class AbstractMockE2eTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected static void registerWebSocketProperties(DynamicPropertyRegistry registry, int websocketPort) {
        registry.add("mass.websocket.port", () -> websocketPort);
    }

    protected static void registerWebSocketPropertiesWithClientUri(DynamicPropertyRegistry registry, int websocketPort) {
        registerWebSocketProperties(registry, websocketPort);
        registry.add("mock.client.uri", () -> "ws://127.0.0.1:" + websocketPort + "/ws");
    }

    protected static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate free port", e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> exchange(String path, HttpMethod method, Object body) {
        String url = "http://127.0.0.1:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> task(Map<String, Object> response) {
        return (Map<String, Object>) response.get("task");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> messages(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("messages");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> stateValidation(Map<String, Object> response) {
        return (Map<String, Object>) response.get("stateValidation");
    }

    @SuppressWarnings("unchecked")
    protected List<String> violations(Map<String, Object> validation) {
        return (List<String>) validation.get("violations");
    }

    protected String createTaskId(String taskName, String textContent, List<String> targets, int batchSize) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("sharedConfig", java.util.Map.of("textContent", textContent));
        createBody.put("userId", "itest");
        createBody.put("targetList", targets);
        createBody.put("batchSize", batchSize);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));
        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    protected String createTaskId(String taskName, String textContent, String target) {
        return createTaskId(taskName, textContent, List.of(target), 1);
    }

    protected String createTaskId(String taskName, String... targets) {
        return createTaskId(taskName, taskName + " integration", List.of(targets), 1);
    }

    protected Map<String, Object> audit(String taskId, String comment) {
        return exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=" + comment, HttpMethod.POST, null);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskSnapshot(taskId, expectedStatus, 20, 250L);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        return waitForTaskSnapshot(taskId,
                snapshot -> expectedStatus.equals(snapshot.task().get("status")),
                expectedStatus,
                maxAttempts,
                sleepMillis);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId,
                                               Predicate<TaskSnapshot> condition,
                                               String expectation,
                                               int maxAttempts,
                                               long sleepMillis) throws InterruptedException {
        TaskSnapshot latestSnapshot = null;
        for (int i = 0; i < maxAttempts; i++) {
            latestSnapshot = fetchTaskSnapshot(taskId);
            if (condition.test(latestSnapshot)) {
                return latestSnapshot;
            }
            Thread.sleep(sleepMillis);
        }
        throw new AssertionError("Task " + taskId + " did not reach expected snapshot: " + expectation
                + ". Last status=" + (latestSnapshot == null ? "<none>" : latestSnapshot.task().get("status"))
                + ", messages=" + (latestSnapshot == null ? 0 : latestSnapshot.messages().size()));
    }

    protected TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        return waitForTaskSnapshot(taskId, "TERMINAL");
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskDetail(taskId, expectedStatus, 20, 250L);
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            if (expectedStatus.equals(task(detailResponse).get("status"))) {
                return detailResponse;
            }
            Thread.sleep(sleepMillis);
        }
        throw new AssertionError("Task " + taskId + " did not reach " + expectedStatus + " within timeout");
    }

    protected TaskSnapshot fetchTaskSnapshot(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> messagesResponse = exchange(
                "/status/api/tasks/" + taskId + "/messages?page=1&size=20",
                HttpMethod.GET,
                null
        );
        return new TaskSnapshot(task(detailResponse), messages(messagesResponse));
    }

    protected record TaskSnapshot(Map<String, Object> task, List<Map<String, Object>> messages) {
    }
}
