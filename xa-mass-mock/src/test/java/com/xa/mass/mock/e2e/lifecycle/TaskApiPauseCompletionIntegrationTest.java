package com.xa.mass.mock.e2e.lifecycle;

import com.google.gson.Gson;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.MessageResult;
import com.xa.mass.gateway.session.SessionRoles;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
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
                "mass.mock.data.tokens=mock/test_mock_tokens.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiPauseCompletionIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void pausedRunningTaskClosesToTerminalWhenRealCallbacksArrive() throws Exception {
        String taskId = createTaskId("pause-completion", "pause completion integration", List.of("target-a", "target-b"), 1);

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
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> task(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertEquals(Boolean.TRUE, detailResponse.get("success"));
        return (Map<String, Object>) detailResponse.get("task");
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
