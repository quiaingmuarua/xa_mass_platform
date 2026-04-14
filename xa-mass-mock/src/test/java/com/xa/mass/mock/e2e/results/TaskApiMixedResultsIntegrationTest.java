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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a task with one SUCCESS and one FAILED message closes to TERMINAL
 * with terminalReason=MIXED_MESSAGE_RESULTS and taskSuccessNumber reflecting only successes.
 *
 * <p>This covers the {@code determineTerminalReason()} MIXED branch which is not exercised
 * by any other integration test (all-succeed and all-fail are already covered).
 */
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
class TaskApiMixedResultsIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void taskWithOneSuccessAndOneFailureClosesToTerminalWithMixedReason() throws Exception {
        // Arrange: create a task with 2 targets so we get 2 TaskMsg rows.
        String taskId = createTaskId("mixed-results", "mixed results integration test", List.of("target-a", "target-b"), 1);

        // Act: approve triggers READY → RUNNING assignment.
        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=mixed-results",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));

        // Wait for RUNNING with 2 messages assigned to devices.
        TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
        assertEquals(2, ((Number) runningSnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(2, runningSnapshot.messages().size());
        assertTrue(runningSnapshot.messages().stream().allMatch(m -> "SENT".equals(m.get("status"))));

        // Submit SUCCESS for the first message and FAILED for the second via the real gateway.
        Map<String, Object> firstMsg = runningSnapshot.messages().get(0);
        Map<String, Object> secondMsg = runningSnapshot.messages().get(1);

        AckSnapshot ack1 = submitTaskResult(
                taskId,
                String.valueOf(firstMsg.get("msgId")),
                String.valueOf(firstMsg.get("deviceId")),
                "SUCCESS",
                "mixed-ok"
        );
        assertEquals(200, ack1.code());
        assertEquals("task result processed", ack1.message());

        AckSnapshot ack2 = submitTaskResult(
                taskId,
                String.valueOf(secondMsg.get("msgId")),
                String.valueOf(secondMsg.get("deviceId")),
                "FAILED",
                "mixed-fail"
        );
        assertEquals(200, ack2.code());
        assertEquals("task result processed", ack2.message());

        // Assert: task closes to TERMINAL with MIXED_MESSAGE_RESULTS.
        TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals("MIXED_MESSAGE_RESULTS", terminalSnapshot.task().get("terminalReason"));
        // taskSuccessNumber counts only successes.
        assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, ((Number) terminalSnapshot.task().get("scheduleDeviceCnt")).intValue());

        assertEquals(2, terminalSnapshot.messages().size());
        long successCount = terminalSnapshot.messages().stream()
                .filter(m -> "SUCCESS".equals(m.get("status"))).count();
        long failedCount = terminalSnapshot.messages().stream()
                .filter(m -> "FAILED".equals(m.get("status"))).count();
        assertEquals(1, successCount);
        assertEquals(1, failedCount);

        // Both messages must have device/token/batch binding.
        for (Map<String, Object> msg : terminalSnapshot.messages()) {
            assertNotNull(msg.get("deviceId"));
            assertNotNull(msg.get("tokenId"));
            assertNotNull(msg.get("batchId"));
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private AckSnapshot submitTaskResult(
            String taskId, String msgId, String deviceId, String status, String detail
    ) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayClient client = new ReplayClient(uri, deviceId, msgId);
        try {
            assertTrue(client.connectBlocking(), "ReplayClient failed to connect for device " + deviceId);
            client.sendMessage(buildResultPayload(taskId, msgId, deviceId, status, detail));
            assertTrue(client.awaitAck(3, TimeUnit.SECONDS), "Timed out waiting for gateway ack on msg " + msgId);
            return client.ackSnapshot();
        } finally {
            client.disconnect();
        }
    }

    private String buildResultPayload(
            String taskId, String msgId, String deviceId, String status, String detail
    ) {
        MassMessage msg = new MassMessage();
        msg.setMsgId(msgId);
        msg.setResponse(true);
        msg.setMsgType(MessageType.TASK);
        msg.setSubMsgType("step");
        msg.setFrom(MessageDirection.CLIENT);
        msg.setProject("demoApp");

        MessageContext ctx = new MessageContext();
        ctx.setTid(taskId);
        ctx.setDeviceId(deviceId);
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        msg.setContext(ctx);
        msg.setPayload(GSON.toJsonTree(Map.of("status", status, "mockData", detail)));
        return GSON.toJson(msg);
    }

    private record AckSnapshot(int code, String message) {}

    /** Connects as a device, sends a pre-built result payload, and captures the gateway ACK. */
    private static final class ReplayClient extends MassWebSocketClientImpl {
        private final String expectedMsgId;
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private volatile AckSnapshot ack;

        ReplayClient(URI uri, String deviceId, String expectedMsgId) {
            super(uri, deviceId);
            this.expectedMsgId = expectedMsgId;
        }

        @Override
        public void onMessage(String message) {
            try {
                MassMessage m = GSON.fromJson(message, MassMessage.class);
                if (m != null && m.isResponse()
                        && m.getMsgType() == MessageType.TASK
                        && expectedMsgId.equals(m.getMsgId())) {
                    MessageResult r = GSON.fromJson(m.getPayload(), MessageResult.class);
                    ack = new AckSnapshot(r.getCode(), r.getMessage());
                    ackLatch.countDown();
                }
            } catch (Exception ignored) {
                // Keep waiting.
            }
            super.onMessage(message);
        }

        boolean awaitAck(long timeout, TimeUnit unit) throws InterruptedException {
            return ackLatch.await(timeout, unit);
        }

        AckSnapshot ackSnapshot() { return ack; }
    }
}
