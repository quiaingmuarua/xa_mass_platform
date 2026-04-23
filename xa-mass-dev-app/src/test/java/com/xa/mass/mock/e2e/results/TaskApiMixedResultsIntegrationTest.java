package com.xa.mass.mock.e2e.results;

import com.google.gson.Gson;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageAckPayload;
import com.xa.mass.gateway.model.massMessage.MessageContext;
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

import static org.junit.jupiter.api.Assertions.*;

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
                "mock.client.workers-config=mock/test_mock_workers.json",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
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
        // Arrange: create a task with 2 targets — disable retries so the first FAILED is final.
        java.util.Map<String, Object> createBody = new java.util.LinkedHashMap<>();
        createBody.put("taskName", "mixed-results");
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", Map.of("textContent", "mixed results integration test", "routingCode", "us"));
        createBody.put("userId", "itest");
        createBody.put("inputs", List.of(
                Map.of("target", "target-a"),
                Map.of("target", "target-b")
        ));
        createBody.put("batchSize", 1);
        createBody.put("defaultMsgMaxRetryCount", 0);
        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));

        // Act: approve triggers READY → RUNNING assignment.
        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=mixed-results",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);

        // Wait for RUNNING with 2 messages assigned to workers.
        TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
        assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(2, runningSnapshot.messages().size());
        assertTrue(runningSnapshot.messages().stream().allMatch(m -> "ASSIGNED".equals(m.get("status"))));

        // Submit SUCCESS for the first message and FAILED for the second via the real gateway.
        Map<String, Object> firstMsg = runningSnapshot.messages().get(0);
        Map<String, Object> secondMsg = runningSnapshot.messages().get(1);

        AckSnapshot ack1 = submitTaskResult(
                taskId,
                String.valueOf(firstMsg.get("msgId")),
                String.valueOf(firstMsg.get("latestAttemptWorkerId")),
                "SUCCESS",
                "mixed-ok"
        );
        assertEquals(200, ack1.code());
        assertEquals("task result processed", ack1.message());

        AckSnapshot ack2 = submitTaskResult(
                taskId,
                String.valueOf(secondMsg.get("msgId")),
                String.valueOf(secondMsg.get("latestAttemptWorkerId")),
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
        assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());

        assertEquals(2, terminalSnapshot.messages().size());
        long successCount = terminalSnapshot.messages().stream()
                .filter(m -> "SUCCESS".equals(m.get("status"))).count();
        long failedCount = terminalSnapshot.messages().stream()
                .filter(m -> "FAILED".equals(m.get("status"))).count();
        assertEquals(1, successCount);
        assertEquals(1, failedCount);

        // Both messages must have worker/workerContext/batch binding.
        for (Map<String, Object> msg : terminalSnapshot.messages()) {
            assertNotNull(msg.get("latestAttemptWorkerId"));
            assertNotNull(msg.get("latestAttemptWorkerContextId"));
            assertNotNull(msg.get("latestAttemptBatchId"));
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private AckSnapshot submitTaskResult(
            String taskId, String msgId, String workerId, String status, String detail
    ) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayClient client = new ReplayClient(uri, workerId, msgId);
        try {
            assertClientConnects(client, "ReplayClient failed to connect for worker " + workerId);
            client.sendMessage(buildResultPayload(taskId, msgId, workerId, status, detail));
            assertTrue(client.awaitAck(3, TimeUnit.SECONDS), "Timed out waiting for gateway ack on msg " + msgId);
            return client.ackSnapshot();
        } finally {
            client.disconnect();
        }
    }

    private String buildResultPayload(
            String taskId, String msgId, String workerId, String status, String detail
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
        ctx.setWorkerId(workerId);
        ctx.setConnRole(SessionRoles.TASK_MESSAGES);
        msg.setContext(ctx);
        msg.setPayload(GSON.toJsonTree(Map.of("status", status, "mockData", detail)));
        return GSON.toJson(msg);
    }

    private record AckSnapshot(int code, String message) {}

    /** Connects as a worker, sends a pre-built result payload, and captures the gateway ACK. */
    private static final class ReplayClient extends MassWebSocketClientImpl {
        private final String expectedMsgId;
        private final CountDownLatch ackLatch = new CountDownLatch(1);
        private volatile AckSnapshot ack;

        ReplayClient(URI uri, String workerId, String expectedMsgId) {
            super(uri, workerId);
            this.expectedMsgId = expectedMsgId;
        }

        @Override
        public void onMessage(String message) {
            try {
                MassMessage m = GSON.fromJson(message, MassMessage.class);
                if (m != null && m.isResponse()
                        && m.getMsgType() == MessageType.TASK
                        && expectedMsgId.equals(m.getMsgId())) {
            MessageAckPayload r = GSON.fromJson(m.getPayload(), MessageAckPayload.class);
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
