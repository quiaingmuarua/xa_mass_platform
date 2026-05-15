package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceAssignmentRequest;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
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
class TaskApiAssignmentTraceObservedIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = Path.of(
            "target",
            "assignment-trace-observed",
            UUID.randomUUID().toString()
    ).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.trace.sink.output-dir", () -> TRACE_OUTPUT_DIR.toString());
    }

    @Test
    void successfulAssignmentIsObservedThroughCanonicalTraceScenario() throws Exception {
        String workerId = "trace-observed-worker-0";
        registerSdkWorkerWithContext(workerId, "us");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "trace-observed sample client failed to connect");
            assertTrue(awaitCondition(() -> app.isWorkerOnline(workerId), 20, 100L),
                    "worker must become transport-online before approval");

            String taskId = createTaskId("trace-observed-assignment", "trace observed assignment", "target-trace");
            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());

            TraceOperatorService traceOperator = new TraceOperatorService();
            TraceAnalyzeResponse response = awaitTraceScenarioOk(traceOperator, taskId);
            assertTrue(response.eventTypeCounts().containsKey("ASSIGNMENT_SUMMARY"),
                    "canonical trace must include ASSIGNMENT_SUMMARY");
            assertTrue(response.eventTypeCounts().containsKey("DISPATCH_BINDING_SUMMARY"),
                    "canonical trace must include DISPATCH_BINDING_SUMMARY");
            assertTrue(traceOperator.assignment(new TraceAssignmentRequest(
                    TRACE_OUTPUT_DIR.toString(),
                    taskId,
                    100
            )).count() > 0, "assignment query must read canonical JSONL rows");
        } finally {
            client.disconnect();
        }
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator, String taskId)
            throws InterruptedException {
        TraceAnalyzeResponse latestResponse = null;
        Exception latestException = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                latestResponse = traceOperator.analyze(new TraceAnalyzeRequest(
                        TRACE_OUTPUT_DIR.toString(),
                        "assignment-success-binding",
                        taskId
                ));
                if (latestResponse.ok()) {
                    return latestResponse;
                }
            } catch (Exception e) {
                latestException = e;
            }
            TimeUnit.MILLISECONDS.sleep(200L);
        }
        if (latestException != null) {
            throw new AssertionError("trace scenario analysis failed before canonical JSONL became readable",
                    latestException);
        }
        throw new AssertionError("trace scenario analysis did not pass. Last response=" + latestResponse);
    }
}
