package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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
class TaskApiTerminateRunningIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void terminateStopsTaskAfterAssignmentButBeforeAnyClientResult() throws Exception {
        java.net.URI wsUri = java.net.URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        HoldingWebSocketClient firstClient = connectClientWithRetries(
                () -> new HoldingWebSocketClient(wsUri, "it-worker-0"),
                "First sample worker failed to connect"
        );
        HoldingWebSocketClient secondClient = connectClientWithRetries(
                () -> new HoldingWebSocketClient(wsUri, "it-worker-1"),
                "Second sample worker failed to connect"
        );
        try {
            assertMinOnlineWorkers(2);

            String taskId = createTaskId(
                    "terminate-running",
                    "terminate running integration",
                    List.of("target-a", "target-b"),
                    1
            );

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId, "RUNNING", 20, 250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(0, ((Number) runningSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, runningSnapshot.stats().inflightCount());

            assertNotNull(firstClient.awaitTask(3, TimeUnit.SECONDS), "First worker did not receive a task dispatch");
            assertNotNull(secondClient.awaitTask(3, TimeUnit.SECONDS), "Second worker did not receive a task dispatch");

            Map<String, Object> terminateResponse = terminateTask(taskId);
            assertApiOk(terminateResponse);

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 250L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals(0, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(0, terminalSnapshot.stats().successCount());
            assertEquals(0, terminalSnapshot.stats().processingCount());
            assertTrue(terminalSnapshot.activeLeases().isEmpty(), "terminate must release in-flight runtime leases");

            ResponseEntity<Map> deleteResponse = restTemplate.exchange(
                    "http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId,
                    HttpMethod.DELETE,
                    org.springframework.http.HttpEntity.EMPTY,
                    Map.class
            );
            assertEquals(405, deleteResponse.getStatusCode().value());

            ResponseEntity<Map> taskResponseAfterDeleteAttempt = restTemplate.exchange(
                    "http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId,
                    HttpMethod.GET,
                    org.springframework.http.HttpEntity.EMPTY,
                    Map.class
            );
            assertEquals(200, taskResponseAfterDeleteAttempt.getStatusCode().value());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class HoldingWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private HoldingWebSocketClient(java.net.URI serverUri, String workerId) {
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
                // Fall through to the base client for non-task frames.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }
    }
}

