package com.xa.mass.server.e2e.results;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ManualAckWebSocketWorkerClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * Verifies that a task with one SUCCESS and one FAILED message closes to TERMINAL
 * with terminalReason=MIXED_MESSAGE_RESULTS and taskSuccessNumber reflecting only successes.
 *
 * <p>This covers the {@code determineTerminalReason()} MIXED branch which is not exercised
 * by any other integration test (all-succeed and all-fail are already covered).
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
@Tag("secondary-proof")
public class TaskApiMixedResultsIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void taskWithOneSuccessAndOneFailureClosesToTerminalWithMixedReason() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketWorkerClient(wsUri, "it-worker-0"),
                "First sample worker failed to connect"
        );
        ManualAckWebSocketWorkerClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketWorkerClient(wsUri, "it-worker-1"),
                "Second sample worker failed to connect"
        );
        try {
            java.util.Map<String, Object> createBody = new java.util.LinkedHashMap<>();
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "mixed results integration test", "routingCode", "us"));
            createBody.put("userId", "itest");
            createBody.put("sourceRef", "mixed-results");
            createBody.put("executionSpec", Map.of("batchSize", 1));
            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, "demo.dispatch", List.of(
                    Map.of("target", "target-a"),
                    Map.of("target", "target-b")
            )));
            assertApiOk(sealTask(taskId));

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId, "RUNNING", 20, 250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, runningSnapshot.stats().inflightCount());

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch);
            assertNotNull(secondDispatch);

            firstClient.sendSuccess(firstDispatch, "mixed-ok");
            secondClient.sendFailure(secondDispatch, "mixed-fail");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 250L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("MIXED_MESSAGE_RESULTS", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(2, terminalSnapshot.stats().totalCount());
            assertEquals(1, terminalSnapshot.stats().successCount());
            assertEquals(1, terminalSnapshot.stats().failedCount());
            assertEquals(2, terminalSnapshot.stats().finalCount());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }
}
