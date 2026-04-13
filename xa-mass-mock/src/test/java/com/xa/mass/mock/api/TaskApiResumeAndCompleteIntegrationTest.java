package com.xa.mass.mock.api;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
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
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the PAUSED → READY → RUNNING → TERMINAL path via a real resume call
 * followed by device connection and mock callback.
 *
 * <p>Specifically:
 * <ol>
 *   <li>Task is approved while NO devices are available → stays READY, scheduleDeviceCnt=0.</li>
 *   <li>Task is paused (READY → PAUSED).</li>
 *   <li>A matching device is registered and a mock client connects.</li>
 *   <li>Task is resumed (PAUSED → READY); {@code notifyTaskReady} kicks the assign worker.</li>
 *   <li>TaskAssignWorker assigns the task to the new device (READY → RUNNING).</li>
 *   <li>Mock client auto-sends a SUCCESS callback → task closes to TERMINAL.</li>
 * </ol>
 *
 * <p>This path is distinct from {@link TaskApiPauseCompletionIntegrationTest}, which covers
 * callbacks arriving <em>while paused</em> (no resume needed).
 */
@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.devices=mock/test_mock_devices_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiResumeAndCompleteIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeviceManager deviceManager;

    @Test
    void resumedPausedTaskCompletesAfterDeviceConnectsAndSendsCallback() throws Exception {
        // 1. Create and approve a task — no devices online yet.
        String taskId = createTask("resume-and-complete");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=resume-and-complete",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));

        // 2. Task reaches READY but stays there (no matching device).
        TaskSnapshot readySnapshot = waitForTaskStatus(taskId, "READY", 8);
        assertEquals(0, ((Number) readySnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(1, readySnapshot.messages().size());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));

        // 3. Pause the READY task.
        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, pauseResponse.get("success"));

        TaskSnapshot pausedSnapshot = waitForTaskStatus(taskId, "PAUSED", 4);
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        // Message is still INIT — no device was ever assigned.
        assertEquals("INIT", pausedSnapshot.messages().get(0).get("status"));

        // 4. Register a matching device and connect a mock client.
        String deviceId = "resume-device-0";
        registerDevice(deviceId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, deviceId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            // 5. Resume the task: PAUSED → READY → assign worker picks it up → RUNNING → TERMINAL.
            Map<String, Object> resumeResponse = exchange(
                    "/status/api/tasks/" + taskId + "/resume",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, resumeResponse.get("success"));

            // 6. Task should proceed all the way to TERMINAL via the new device.
            TaskSnapshot terminalSnapshot = waitForTaskStatus(taskId, "TERMINAL", 20);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("scheduleDeviceCnt")).intValue());
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskExecutedNumber")).intValue());

            assertEquals(1, terminalSnapshot.messages().size());
            Map<String, Object> msg = terminalSnapshot.messages().get(0);
            assertEquals("SUCCESS", msg.get("status"));
            assertEquals(deviceId, msg.get("deviceId"));
            assertNotNull(msg.get("tokenId"));
            assertNotNull(msg.get("batchId"));
        } finally {
            client.disconnect();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void registerDevice(String deviceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setGroupId("us");
        device.setStatus(DeviceStatus.ONLINE);
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        deviceManager.addDevice(device);

        Token token = new Token();
        token.setTokenId("token-" + deviceId);
        token.setDeviceId(deviceId);
        token.setChannel("us");
        token.setStatus(TokenStatus.LOGIN_READY);
        deviceManager.addToken(deviceId, token);
    }

    private String createTask(String taskName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskName", taskName);
        body.put("project", "demoApp");
        body.put("countryCode", "us");
        body.put("textContent", "resume and complete integration test");
        body.put("userId", "itest");
        body.put("targetList", List.of("target-a"));
        body.put("batchSize", 1);

        Map<String, Object> response = exchange("/status/api/tasks", HttpMethod.POST, body);
        assertEquals(Boolean.TRUE, response.get("success"));
        String taskId = String.valueOf(response.get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    private TaskSnapshot waitForTaskStatus(String taskId, String expectedStatus, int maxAttempts)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Map<String, Object> detail = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            Map<String, Object> msgs = exchange(
                    "/status/api/tasks/" + taskId + "/messages?page=1&size=20", HttpMethod.GET, null);
            Map<String, Object> task = task(detail);
            List<Map<String, Object>> messages = messages(msgs);
            if (expectedStatus.equals(task.get("status"))) {
                return new TaskSnapshot(task, messages);
            }
            Thread.sleep(500L);
        }
        throw new AssertionError("Task " + taskId + " did not reach " + expectedStatus + " within timeout");
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
        ResponseEntity<Map> response = restTemplate.exchange(
                "http://127.0.0.1:" + port + path, method, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate free port", e);
        }
    }

    private record TaskSnapshot(Map<String, Object> task, List<Map<String, Object>> messages) {}
}
