package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class TaskApiSingleWorkerReuseTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("single-worker-reuse-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void releasedWorkerIsReusedByLaterTaskAndObservedThroughCanonicalTrace() throws Exception {
        String workerId = "reuse-trace-worker-0";
        registerSdkStatelessWorkerWithAttributes(
                workerId,
                "pool-reuse",
                "demoApp",
                java.util.Map.of("routingTags", "us", "country", "us")
        );

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "single worker reuse trace client failed to connect");

            String firstTaskId = createTaskId("reuse-trace-first", "single worker reuse trace first", "target-a");
            assertApiOk(approveTask(firstTaskId));
            RuntimeTaskSnapshot firstTerminal = waitForTerminalRuntimeTask(firstTaskId);
            assertEquals("ALL_MESSAGES_SUCCEEDED", firstTerminal.task().get("terminalReason"));
            assertEquals(1, firstTerminal.stats().successCount());
            assertEquals(1, ((Number) firstTerminal.task().get("peakAssignedWorkerCount")).intValue());
            assertTrue(firstTerminal.activeLeases().isEmpty());

            TraceAnalyzeResponse cleanupTrace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "worker-resource-cleanup-without-context",
                    firstTaskId
            );
            assertTrue(cleanupTrace.eventTypeCounts().containsKey("RESOURCE_RELEASED"),
                    "canonical trace must include worker resource release before reuse");

            String secondTaskId = createTaskId("reuse-trace-second", "single worker reuse trace second", "target-b");
            assertApiOk(approveTask(secondTaskId));
            RuntimeTaskSnapshot secondTerminal = waitForTerminalRuntimeTask(secondTaskId);
            assertEquals("ALL_MESSAGES_SUCCEEDED", secondTerminal.task().get("terminalReason"));
            assertEquals(1, secondTerminal.stats().successCount());
            assertEquals(1, ((Number) secondTerminal.task().get("peakAssignedWorkerCount")).intValue());
            assertTrue(secondTerminal.activeLeases().isEmpty());
        } finally {
            client.disconnect();
        }
    }
}
