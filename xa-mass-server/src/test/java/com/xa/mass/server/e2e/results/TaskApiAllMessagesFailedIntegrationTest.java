package com.xa.mass.server.e2e.results;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the ALL_MESSAGES_FAILED terminal path via the HTTP/WebSocket shell.
 *
 * <p>This complements the chaos probe {@code SdkPollingAllMessagesFailedChaosRunner} which
 * exercises the same path at the SDK embedded-runtime layer. The Boot-shell path adds:
 * Spring wiring, HTTP controller to engine dispatch, WebSocket transport, and full HTTP
 * callback ingestion across the full cross-module chain.
 *
 * <p>Key distinction from {@link TaskApiFailureResultIntegrationTest}: that test configures
 * {@code sample.client.retry-attempts=1}, so messages exhaust retries and close with
 * {@code finalReason=RETRY_EXHAUSTED}. Here {@code maxRetryCount=0} so each message gets
 * exactly one attempt; the first FAILED submission immediately exhausts the retry budget,
 * so the message still closes with {@code finalReason=RETRY_EXHAUSTED} while the task closes
 * with {@code terminalReason=ALL_MESSAGES_FAILED}.
 */
@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
public class TaskApiAllMessagesFailedIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void allWorkersFailWithNoRetriesClosesTaskWithAllMessagesFailedReason() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-0"),
                "First sample worker failed to connect"
        );
        ManualAckWebSocketClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketClient(wsUri, "it-worker-1"),
                "Second sample worker failed to connect"
        );
        try {
            // Create a 2-message task with maxRetryCount=0 so each message gets exactly one attempt.
            Map<String, Object> createBody = Map.of(
                    "project", "demoApp",
                    "sharedConfig", Map.of("textContent", "all messages failed e2e", "routingCode", "us"),
                    "userId", "itest",
                    "sourceRef", "all-messages-failed",
                    "executionSpec", Map.of("batchSize", 1)
            );
            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));

            assertApiOk(appendTaskItems(taskId,
                    "demo.dispatch",
                    List.of(Map.of("target", "target-a"), Map.of("target", "target-b"))
            ));
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            // Task should reach RUNNING with both work items leased to workers.
            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId, "RUNNING", 20, 250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, runningSnapshot.stats().inflightCount());

            // Both workers receive their dispatches.
            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "First worker did not receive a dispatch");
            assertNotNull(secondDispatch, "Second worker did not receive a dispatch");

            // Both workers submit explicit FAILED results with no retries available.
            firstClient.sendResult(firstDispatch, "FAILED", "all-failed-test");
            secondClient.sendResult(secondDispatch, "FAILED", "all-failed-test");

            RuntimeTaskSnapshot terminalSnapshot = waitForTerminalRuntimeTask(taskId);

            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_FAILED", terminalSnapshot.task().get("terminalReason"),
                    "task with all explicitly-failed messages should close with ALL_MESSAGES_FAILED");
            assertEquals(0, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, terminalSnapshot.stats().totalCount());
            assertEquals(0, terminalSnapshot.stats().successCount());
            assertEquals(2, terminalSnapshot.stats().failedCount());
            assertEquals(2, terminalSnapshot.stats().finalCount());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        ManualAckWebSocketClient(URI uri, String workerId) {
            super(uri, workerId);
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
                // Fall through to base handler.
            }
            super.onMessage(message);
        }

        JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        void sendResult(JsonObject taskFrame, String status, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskFrame),
                    WsFrameTestSupport.project(taskFrame),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskFrame),
                    status,
                    detail
            ));
        }
    }
}


