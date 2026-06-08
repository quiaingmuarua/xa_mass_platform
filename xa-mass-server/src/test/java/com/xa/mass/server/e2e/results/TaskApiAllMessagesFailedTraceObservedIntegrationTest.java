package com.xa.mass.server.e2e.results;

import com.google.gson.JsonObject;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.e2e.support.ManualAckWebSocketWorkerClient;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.trace.sink.enabled=true",
                "mass.trace.sink.queue-capacity=256",
                "mass.trace.sink.rotate-after-lines=1",
                "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                "mass.trace.sink.shutdown-drain-timeout-ms=1500"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
public class TaskApiAllMessagesFailedTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("all-messages-failed-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void allFailedCallbacksConvergeToTerminalThroughCanonicalTrace() throws Exception {
        registerSdkWorkerWithContext("all-failed-trace-worker-0", "us");
        registerSdkWorkerWithContext("all-failed-trace-worker-1", "us");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient firstClient = connectClientWithRetries(
                () -> new ManualAckWebSocketWorkerClient(wsUri, "all-failed-trace-worker-0"),
                "first all-failed trace worker failed to connect"
        );
        ManualAckWebSocketWorkerClient secondClient = connectClientWithRetries(
                () -> new ManualAckWebSocketWorkerClient(wsUri, "all-failed-trace-worker-1"),
                "second all-failed trace worker failed to connect"
        );
        try {
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "all messages failed trace observed", "routingCode", "us"));
            createBody.put("userId", "itest");
            createBody.put("sourceRef", "all-messages-failed-trace");
            createBody.put("executionSpec", Map.of("batchSize", 1));
            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId,
                    "demo.dispatch",
                    List.of(Map.of("target", "target-a"), Map.of("target", "target-b"))
            ));
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot runningSnapshot = waitForRuntimeTaskSnapshot(taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.stats().inflightCount() == 2,
                    "RUNNING with two inflight work items",
                    20,
                    250L);
            assertEquals(2, ((Number) runningSnapshot.task().get("peakAssignedWorkerCount")).intValue());

            JsonObject firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(firstDispatch, "first all-failed trace worker should receive dispatch");
            assertNotNull(secondDispatch, "second all-failed trace worker should receive dispatch");

            firstClient.sendFailure(firstDispatch, "all-failed-trace");
            secondClient.sendFailure(secondDispatch, "all-failed-trace");

            RuntimeTaskSnapshot terminalSnapshot = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_FAILED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(0, terminalSnapshot.stats().successCount());
            assertEquals(2, terminalSnapshot.stats().failedCount());
            assertEquals(2, terminalSnapshot.stats().finalCount());
            assertEquals(2, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "all-failed-terminal-convergence",
                    taskId
            );
            assertTrue(trace.eventTypeCounts().containsKey("TASK_TERMINAL_CLOSED"),
                    "canonical trace must include terminal convergence for all-failed proof");
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }
}
