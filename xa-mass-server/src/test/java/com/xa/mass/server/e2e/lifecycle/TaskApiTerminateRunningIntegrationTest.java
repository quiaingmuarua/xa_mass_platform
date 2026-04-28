package com.xa.mass.server.e2e.lifecycle;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.MockWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
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
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiTerminateRunningIntegrationTest extends AbstractMockE2eTest {

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
                "First mock worker failed to connect"
        );
        HoldingWebSocketClient secondClient = connectClientWithRetries(
                () -> new HoldingWebSocketClient(wsUri, "it-worker-1"),
                "Second mock worker failed to connect"
        );
        try {
            assertMinOnlineWorkers(2);

            String taskId = createTaskId(
                    "terminate-running",
                    "terminate running integration",
                    List.of("target-a", "target-b"),
                    1
            );

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=approve",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(approveResponse);

            TaskSnapshot runningSnapshot = waitForTaskSnapshot(taskId, "RUNNING");
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(0, ((Number) runningSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, runningSnapshot.messages().size());
            assertTrue(runningSnapshot.messages().stream().allMatch(message -> "ASSIGNED".equals(message.get("status"))));

            assertNotNull(firstClient.awaitTask(3, TimeUnit.SECONDS), "First worker did not receive a task dispatch");
            assertNotNull(secondClient.awaitTask(3, TimeUnit.SECONDS), "Second worker did not receive a task dispatch");

            Map<String, Object> terminateResponse = exchange(
                    "/status/api/tasks/" + taskId + "/terminate",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(terminateResponse);

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL");
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals(0, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, terminalSnapshot.messages().size());
            assertTrue(terminalSnapshot.messages().stream().allMatch(message -> "EXPIRED".equals(message.get("status"))));

            Map<String, Object> deleteResponse = exchange(
                    "/status/api/tasks/" + taskId,
                    HttpMethod.DELETE,
                    null
            );
            assertApiOk(deleteResponse);

            ResponseEntity<Map> deletedTaskResponse = restTemplate.exchange(
                    "http://127.0.0.1:" + port + "/status/api/tasks/" + taskId,
                    HttpMethod.GET,
                    org.springframework.http.HttpEntity.EMPTY,
                    Map.class
            );
            assertEquals(404, deletedTaskResponse.getStatusCode().value());
            assertEquals(404, ((Number) deletedTaskResponse.getBody().get("code")).intValue());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private static final class HoldingWebSocketClient extends MockWorkerWebSocketClient {
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
