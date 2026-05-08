package com.xa.mass.server.e2e.results;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "sample.client.retry-attempts=1",
                "sample.client.retry-delay=1",
                "sample.client.connection-timeout=5",
                "sample.client.ping-delay=60",
                "sample.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiCallbackReplayIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void duplicateWebSocketCallbackReplayKeepsFirstFinalMessageState() throws Exception {
        String taskId = createTaskId("integration-task-callback-replay", "integration callback replay", List.of("target-a", "target-b"), 1);

        Map<String, Object> auditResponse = exchange(
                "/api/v1/tasks/" + taskId + ":approve",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        TaskSnapshot terminalSnapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
        assertEquals(2, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(2, terminalSnapshot.messages().size());

        Map<String, Object> firstMessage = terminalSnapshot.messages().get(0);
        String messageId = String.valueOf(firstMessage.get("messageId"));
        String originalStatus = String.valueOf(firstMessage.get("status"));
        Object originalResult = firstMessage.get("result");
        Object originalErrorMessage = firstMessage.get("errorMessage");
        assertEquals("SUCCESS", originalStatus);
        assertNull(originalErrorMessage);

        replayConflictingTaskResult(taskId, messageId, "FAILED", "replayed-conflict");

        TaskSnapshot afterReplay = waitForTaskSnapshot(taskId, "TERMINAL");
        Map<String, Object> replayedMessage = findMessage(afterReplay.messages(), messageId);

        assertEquals("TERMINAL", afterReplay.task().get("status"));
        assertEquals(2, ((Number) afterReplay.task().get("taskSuccessNumber")).intValue());
        assertNotNull(replayedMessage);
        assertEquals(originalStatus, replayedMessage.get("status"));
        assertEquals(originalResult, replayedMessage.get("result"));
        assertEquals(originalErrorMessage, replayedMessage.get("errorMessage"));
        assertNull(replayedMessage.get("errorMessage"));
    }

    private void replayConflictingTaskResult(String taskId, String messageId, String status, String detail) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ReplayWebSocketClient client = new ReplayWebSocketClient(uri, "replay-worker");
        try {
            assertClientConnects(client, "Replay WebSocket client failed to connect");
            client.sendMessage(WsFrameTestSupport.buildTaskResult(messageId, "demoApp", "replay-worker", taskId, status, detail));
            client.awaitSilence(300, TimeUnit.MILLISECONDS);
        } finally {
            client.disconnect();
        }
    }

    private Map<String, Object> findMessage(List<Map<String, Object>> messages, String messageId) {
        return messages.stream()
                .filter(message -> messageId.equals(String.valueOf(message.get("messageId"))))
                .findFirst()
                .orElse(null);
    }

    private static final class ReplayWebSocketClient extends SampleWorkerWebSocketClient {
        private ReplayWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            super.onMessage(message);
        }

        private void awaitSilence(long timeout, TimeUnit unit) throws InterruptedException {
            unit.sleep(timeout);
        }
    }
}

