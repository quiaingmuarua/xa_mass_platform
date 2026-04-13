package com.xa.mass.mock.api;

import com.google.gson.Gson;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.MessageResult;
import com.xa.mass.gateway.session.SessionRoles;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mock.client.devices-config=mock/test_mock_devices.json",
                "mass.mock.data.devices=mock/test_mock_devices.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiPauseCompletionIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("mass.websocket.port", () -> WEBSOCKET_PORT);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void pausedRunningTaskClosesToTerminalWhenRealCallbacksArrive() throws Exception {
        String taskId = createTask("pause-completion");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));

        TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
        assertEquals(2, ((Number) runningSnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(2, runningSnapshot.messages().size());
        assertTrue(runningSnapshot.messages().stream().allMatch(message -> "SENT".equals(message.get("status"))));

        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, pauseResponse.get("success"));

        TaskSnapshot pausedSnapshot = waitForTaskSnapshot(taskId, "PAUSED");
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        assertEquals(2, pausedSnapshot.messages().size());
        assertTrue(pausedSnapshot.messages().stream().allMatch(message -> "SENT".equals(message.get("status"))));

        for (Map<String, Object> message : pausedSnapshot.messages()) {
            String msgId = String.valueOf(message.get("msgId"));
            String deviceId = String.valueOf(message.get("deviceId"));
            AckSnapshot ack = submitTaskResult(taskId, msgId, deviceId, "SUCCESS", "paused-complete-" + deviceId);
            assertEquals(200, ack.code());
            assertEquals("task result processed", ack.message());
        }

        TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskExecutedNumber")).intValue());
        assertEquals(2, terminalSnapshot.messages().size());
        for (Map<String, Object> message : terminalSnapshot.messages()) {
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("deviceId"));
            assertNotNull(message.get("tokenId"));
            assertNotNull(message.get("batchId"));
        }
    }

    private AckSnapshot submitTaskResult(String taskId, String msgId, String deviceId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, deviceId, msgId);
        try {
            assertTrue(client.connectBlocking(), "Replay WebSocket client failed to connect");
            client.sendMessage(buildTaskResultPayload(taskId, msgId, deviceId, status, detail));
            assertTrue(client.awaitAck(3, TimeUnit.SECONDS), "Timed out waiting for gateway ack");
            return client.ackSnapshot();
        } finally {
            client.disconnect();
        }
    }

    private String buildTaskResultPayload(String taskId, String msgId, String deviceId, String status, String detail) {
        MassMessage result = new MassMessage();
        result.setMsgId(msgId);
        result.setResponse(true);
        result.setMsgType(MessageType.TASK);
        result.setSubMsgType("step");
        result.setFrom(MessageDirection.CLIENT);
        result.setProject("demoApp");

        MessageContext context = new MessageContext();
        context.setTid(taskId);
        context.setDeviceId(deviceId);
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        result.setContext(context);
        result.setPayload(GSON.toJsonTree(Map.of(
                "status", status,
                "mockData", detail
        )));
        return GSON.toJson(result);
    }

    private String createTask(String taskName) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("textContent", "pause completion integration");
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

    private record AckSnapshot(int code, String message) {
    }

    private static final class ReplayWebSocketClient extends MassWebSocketClientImpl {
        private final String expectedMsgId;
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private volatile AckSnapshot ackSnapshot;

        private ReplayWebSocketClient(URI serverUri, String deviceId, String expectedMsgId) {
            super(serverUri, deviceId);
            this.expectedMsgId = expectedMsgId;
        }

        @Override
        public void onMessage(String message) {
            try {
                MassMessage massMessage = GSON.fromJson(message, MassMessage.class);
                if (massMessage != null
                        && massMessage.isResponse()
                        && massMessage.getMsgType() == MessageType.TASK
                        && expectedMsgId.equals(massMessage.getMsgId())) {
                    MessageResult result = GSON.fromJson(massMessage.getPayload(), MessageResult.class);
                    ackSnapshot = new AckSnapshot(result.getCode(), result.getMessage());
                    ackLatch.countDown();
                }
            } catch (Exception ignored) {
                // Keep waiting for the expected task ack frame.
            }
            super.onMessage(message);
        }

        private boolean awaitAck(long timeout, TimeUnit unit) throws InterruptedException {
            return ackLatch.await(timeout, unit);
        }

        private AckSnapshot ackSnapshot() {
            return ackSnapshot;
        }
    }
}
