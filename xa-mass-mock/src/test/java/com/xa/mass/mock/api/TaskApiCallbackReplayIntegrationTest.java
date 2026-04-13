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
import static org.junit.jupiter.api.Assertions.assertNull;
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
@DirtiesContext
class TaskApiCallbackReplayIntegrationTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

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
    void duplicateGatewayCallbackReplayKeepsFirstFinalMessageState() throws Exception {
        String taskId = createTask("integration-task-callback-replay");

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=integration-replay",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        TaskSnapshot terminalSnapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskExecutedNumber")).intValue());
        assertEquals(2, terminalSnapshot.messages().size());

        Map<String, Object> firstMessage = terminalSnapshot.messages().get(0);
        String msgId = String.valueOf(firstMessage.get("msgId"));
        String originalStatus = String.valueOf(firstMessage.get("status"));
        Object originalResult = firstMessage.get("result");
        Object originalErrorMessage = firstMessage.get("errorMessage");
        assertEquals("SUCCESS", originalStatus);
        assertNull(originalErrorMessage);

        AckSnapshot ack = replayConflictingTaskResult(taskId, msgId, "FAILED", "replayed-conflict");
        assertEquals(200, ack.code());
        assertEquals("task result processed", ack.message());

        TaskSnapshot afterReplay = waitForTaskSnapshot(taskId, "TERMINAL");
        Map<String, Object> replayedMessage = findMessage(afterReplay.messages(), msgId);

        assertEquals("TERMINAL", afterReplay.task().get("status"));
        assertEquals(2, ((Number) afterReplay.task().get("taskExecutedNumber")).intValue());
        assertNotNull(replayedMessage);
        assertEquals(originalStatus, replayedMessage.get("status"));
        assertEquals(originalResult, replayedMessage.get("result"));
        assertEquals(originalErrorMessage, replayedMessage.get("errorMessage"));
        assertNull(replayedMessage.get("errorMessage"));
    }

    private AckSnapshot replayConflictingTaskResult(String taskId, String msgId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, "replay-device", msgId);
        try {
            assertTrue(client.connectBlocking(), "Replay WebSocket client failed to connect");
            client.sendMessage(buildReplayPayload(taskId, msgId, status, detail));
            assertTrue(client.awaitAck(3, TimeUnit.SECONDS), "Timed out waiting for gateway ack");
            return client.ackSnapshot();
        } finally {
            client.disconnect();
        }
    }

    private String buildReplayPayload(String taskId, String msgId, String status, String detail) {
        MassMessage replay = new MassMessage();
        replay.setMsgId(msgId);
        replay.setResponse(true);
        replay.setMsgType(MessageType.TASK);
        replay.setSubMsgType("step");
        replay.setFrom(MessageDirection.CLIENT);
        replay.setProject("demoApp");

        MessageContext context = new MessageContext();
        context.setTid(taskId);
        context.setDeviceId("replay-device");
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        replay.setContext(context);
        replay.setPayload(GSON.toJsonTree(Map.of(
                "status", status,
                "mockData", detail
        )));
        return GSON.toJson(replay);
    }

    private String createTask(String taskName) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("countryCode", "us");
        createBody.put("textContent", "integration callback replay");
        createBody.put("userId", "itest");
        createBody.put("targetList", List.of("target-a", "target-b"));
        createBody.put("batchSize", 1);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertEquals(Boolean.TRUE, createResponse.get("success"));
        String taskId = String.valueOf(createResponse.get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    private TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        return waitForTaskSnapshot(taskId, "TERMINAL");
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
        throw new AssertionError("Task did not reach expected status within timeout: " + expectedStatus);
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

    private Map<String, Object> findMessage(List<Map<String, Object>> messages, String msgId) {
        return messages.stream()
                .filter(message -> msgId.equals(String.valueOf(message.get("msgId"))))
                .findFirst()
                .orElse(null);
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
