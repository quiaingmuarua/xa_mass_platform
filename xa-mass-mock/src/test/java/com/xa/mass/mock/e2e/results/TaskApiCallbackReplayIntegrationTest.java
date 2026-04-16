package com.xa.mass.mock.e2e.results;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mock.client.workers-config=mock/test_mock_workers.json",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
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
class TaskApiCallbackReplayIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void duplicateGatewayCallbackReplayKeepsFirstFinalMessageState() throws Exception {
        String taskId = createTaskId("integration-task-callback-replay", "integration callback replay", List.of("target-a", "target-b"), 1);

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=integration-replay",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        TaskSnapshot terminalSnapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
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
        assertEquals(2, ((Number) afterReplay.task().get("taskSuccessNumber")).intValue());
        assertNotNull(replayedMessage);
        assertEquals(originalStatus, replayedMessage.get("status"));
        assertEquals(originalResult, replayedMessage.get("result"));
        assertEquals(originalErrorMessage, replayedMessage.get("errorMessage"));
        assertNull(replayedMessage.get("errorMessage"));
    }

    private AckSnapshot replayConflictingTaskResult(String taskId, String msgId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, "replay-worker", msgId);
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
        context.setWorkerId("replay-worker");
        context.setConnRole(SessionRoles.TASK_MESSAGES);
        replay.setContext(context);
        replay.setPayload(GSON.toJsonTree(Map.of(
                "status", status,
                "mockData", detail
        )));
        return GSON.toJson(replay);
    }

    private Map<String, Object> findMessage(List<Map<String, Object>> messages, String msgId) {
        return messages.stream()
                .filter(message -> msgId.equals(String.valueOf(message.get("msgId"))))
                .findFirst()
                .orElse(null);
    }

    private record AckSnapshot(int code, String message) {
    }

    private static final class ReplayWebSocketClient extends MassWebSocketClientImpl {
        private final String expectedMsgId;
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private volatile AckSnapshot ackSnapshot;

        private ReplayWebSocketClient(URI serverUri, String workerId, String expectedMsgId) {
            super(serverUri, workerId);
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
