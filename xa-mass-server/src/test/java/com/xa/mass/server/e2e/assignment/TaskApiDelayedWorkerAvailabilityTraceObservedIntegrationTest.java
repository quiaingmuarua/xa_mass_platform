package com.xa.mass.server.e2e.assignment;

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
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("late-worker-backfill-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void delayedWorkerBackfillIsObservedThroughCanonicalTrace() throws Exception {
        String taskId = createTaskId(
                "late-worker-trace",
                "late worker backfill trace observed",
                "target-a"
        );
        assertApiOk(approveTask(taskId));

        RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, readySnapshot.stats().readyCount());
        assertEquals(0, readySnapshot.activeLeases().size());

        String lateWorkerId = "late-worker-trace-0";
        registerSdkWorkerWithContext(lateWorkerId, "us");
        assertFalse(app.isWorkerOnline(lateWorkerId),
                "worker registration must not create delayed worker transport presence");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(uri, lateWorkerId);
        try {
            assertClientConnects(client, "late worker trace client failed to connect");
            waitUntil(() -> app.isWorkerOnline(lateWorkerId),
                    "late worker connect must surface transport presence online");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, terminalSnapshot.stats().successCount());

            TraceAnalyzeResponse response = awaitTraceScenarioOk(new TraceOperatorService(), taskId, lateWorkerId);
            assertTrue(response.eventTypeCounts().containsKey("WORKER_MATCH_ACCEPTED"),
                    "canonical trace must include the accepted late worker match");
            assertTrue(response.eventTypeCounts().containsKey("DISPATCH_BINDING_SUMMARY"),
                    "canonical trace must include dispatch binding after late worker backfill");
        } finally {
            client.disconnect();
        }
        waitUntil(() -> !app.isWorkerOnline(lateWorkerId),
                "late worker disconnect must converge transport presence offline");
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator,
                                                      String taskId,
                                                      String lateWorkerId)
            throws InterruptedException {
        return awaitTraceScenarioOk(TRACE_OUTPUT_DIR,
                "late-worker-backfill",
                taskId + "," + lateWorkerId);
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
