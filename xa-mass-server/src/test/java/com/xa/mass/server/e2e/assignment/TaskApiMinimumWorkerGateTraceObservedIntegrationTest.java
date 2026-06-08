package com.xa.mass.server.e2e.assignment;

import com.xa.mass.base.model.Task;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceOperatorService;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class TaskApiMinimumWorkerGateTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("minimum-worker-gate-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void minimumWorkerGateIsObservedThroughCanonicalTraceBeforeTaskCanStart() throws Exception {
        String workerId = "min-gate-trace-worker-0";
        registerSdkWorkerWithContext(workerId, "us");
        assertFalse(app.isWorkerOnline(workerId),
                "worker registration must not create transport presence");
        String secondWorkerId = "min-gate-trace-worker-1";
        registerSdkWorkerWithContext(secondWorkerId, "us");
        assertFalse(app.isWorkerOnline(secondWorkerId),
                "second worker registration must not create transport presence");

        String taskId = createTaskId("min-worker-gate-trace", "minimum worker gate trace observed", "target-a");
        Task task = taskStorage.getTask(taskId).orElseThrow();
        task.setMinRequiredWorkerCount(2);
        assertTrue(updateStoredTask(task));
        assertApiOk(audit(taskId, "min-worker-gate-trace"));

        RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, readySnapshot.stats().readyCount());
        assertEquals(0, readySnapshot.activeLeases().size());

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(uri, workerId);
        SampleWorkerWebSocketClient secondClient = new SampleWorkerWebSocketClient(uri, secondWorkerId);
        try {
            assertClientConnects(client, "minimum worker gate sample client failed to connect");
            waitUntil(() -> app.isWorkerOnline(workerId),
                    "worker connect must surface transport presence online");

            RuntimeTaskSnapshot gatedSnapshot = waitForRuntimeTaskSnapshot(taskId,
                    snapshot -> "READY".equals(snapshot.task().get("status"))
                            && snapshot.activeLeases().isEmpty()
                            && snapshot.stats().readyCount() == 1,
                    "READY with no active lease while minimum worker gate is unsatisfied",
                    20,
                    250L);
            assertEquals(0, ((Number) gatedSnapshot.task().get("peakAssignedWorkerCount")).intValue());

            TraceAnalyzeResponse response = awaitTraceScenarioOk(new TraceOperatorService(), taskId);
            assertTrue(response.eventTypeCounts().containsKey("ASSIGNMENT_SUMMARY"),
                    "canonical trace must include ASSIGNMENT_SUMMARY for gate decision");
            assertTrue(response.eventTypeCounts().containsKey("DISPATCH_SKIPPED"),
                    "canonical trace must include DISPATCH_SKIPPED for minimum worker gate");

            assertClientConnects(secondClient, "second minimum worker gate sample client failed to connect");
            waitUntil(() -> app.isWorkerOnline(secondWorkerId),
                    "second worker connect must surface transport presence online");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, terminalSnapshot.stats().successCount());
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
        } finally {
            secondClient.disconnect();
            client.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(workerId),
                "worker disconnect must converge transport presence offline");
        waitUntil(() -> !app.isWorkerOnline(secondWorkerId),
                "second worker disconnect must converge transport presence offline");
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator, String taskId)
            throws InterruptedException {
        return awaitTraceScenarioOk(TRACE_OUTPUT_DIR, "assignment-min-worker-gate", taskId);
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }
}
