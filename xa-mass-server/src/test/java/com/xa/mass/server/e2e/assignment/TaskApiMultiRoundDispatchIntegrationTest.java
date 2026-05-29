package com.xa.mass.server.e2e.assignment;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.ReviewReadModelSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Tag("secondary-proof")
class TaskApiMultiRoundDispatchIntegrationTest extends ReviewReadModelSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void singleWorkerWithBatchSizeOneCompletesTaskAcrossMultipleRounds() throws Exception {
        String workerId = "round-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = connectClientWithRetries(
                () -> new SampleWorkerWebSocketClient(wsUri, workerId),
                "Sample client failed to connect"
        );
        try {
            String taskId = createTaskId(
                    "multi-round",
                    "single worker multi round dispatch",
                    List.of("target-a", "target-b", "target-c"),
                    1
            );

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(3, terminal.messages().size());

            for (Map<String, Object> message : terminal.messages()) {
                assertEquals("SUCCESS", message.get("status"));
                assertEquals(workerId, message.get("latestAttemptWorkerId"));
            }

        } finally {
            client.disconnect();
        }
    }

    @Test
    void singleWorkerWithBatchSizeTwoWaitsForNextRoundUntilCurrentRoundFinishes() throws Exception {
        String workerId = "round-worker-batch-2";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient client = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, workerId),
                "Manual sample client failed to connect"
        );
        try {
            String taskId = createTaskId(
                    "multi-round-batch-two",
                    "single worker waits for current round to finish",
                    List.of("target-a", "target-b", "target-c"),
                    2
            );

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            JsonObject first = client.awaitTask(3, TimeUnit.SECONDS);
            JsonObject second = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(first, "First round should dispatch the first message");
            assertNotNull(second, "First round should dispatch the second message");
            assertNull(client.awaitTask(750, TimeUnit.MILLISECONDS), "Third message should wait for the next dispatch round");

            TaskSnapshot firstRound = waitForTaskSnapshot(
                    taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.messages().size() == 3
                            && snapshot.messages().stream().filter(msg -> workerId.equals(msg.get("latestAttemptWorkerId"))).count() == 2
                            && snapshot.messages().stream().filter(msg -> msg.get("latestAttemptWorkerId") == null).count() == 1
                            && snapshot.messages().stream().noneMatch(msg -> isTerminalMessageStatus(msg.get("status"))),
                    "RUNNING with two in-flight messages bound to the worker and one pending INIT-style message",
                    20,
                    100L
            );
            assertEquals(2L,
                    firstRound.messages().stream().filter(msg -> workerId.equals(msg.get("latestAttemptWorkerId"))).count());
            assertEquals(1L,
                    firstRound.messages().stream().filter(msg -> msg.get("latestAttemptWorkerId") == null).count());

            client.sendSuccess(first, "round-1-a");
            assertNull(client.awaitTask(750, TimeUnit.MILLISECONDS), "Worker should stay busy until the whole round finishes");

            TaskSnapshot afterFirstResult = waitForTaskSnapshot(
                    taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.messages().size() == 3
                            && snapshot.messages().stream().filter(msg -> "SUCCESS".equals(msg.get("status"))).count() == 1
                            && snapshot.messages().stream().filter(msg -> workerId.equals(msg.get("latestAttemptWorkerId"))).count() == 2
                            && snapshot.messages().stream().filter(msg -> msg.get("latestAttemptWorkerId") == null).count() == 1,
                    "RUNNING with one finished message, one remaining in-flight message, and one pending message",
                    20,
                    100L
            );
            assertEquals(1L,
                    afterFirstResult.messages().stream().filter(msg -> "SUCCESS".equals(msg.get("status"))).count());

            client.sendSuccess(second, "round-1-b");

            JsonObject third = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(third, "Next round should begin after the first round finishes");

            client.sendSuccess(third, "round-2-c");

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS"),
                    terminal.messages().stream().map(msg -> String.valueOf(msg.get("status"))).collect(Collectors.toList()));
            assertEquals(List.of(workerId, workerId, workerId),
                    terminal.messages().stream().map(msg -> String.valueOf(msg.get("latestAttemptWorkerId"))).collect(Collectors.toList()));
        } finally {
            client.disconnect();
        }
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }

    private boolean isTerminalMessageStatus(Object status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}
