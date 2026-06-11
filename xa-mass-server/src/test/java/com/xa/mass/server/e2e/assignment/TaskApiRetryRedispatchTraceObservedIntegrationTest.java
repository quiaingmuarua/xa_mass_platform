package com.xa.mass.server.e2e.assignment;

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
                "mass.trace.sink.shutdown-drain-timeout-ms=1500",
                "mass.engine.assignment-retry-delay-millis=100"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext
class TaskApiRetryRedispatchTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("retry-redispatch-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @Test
    void interactiveRetryRedispatchIsObservedThroughCanonicalTraceBeforeLateWorkerSuccess() throws Exception {
        String workerId = "retry-redispatch-worker";
        registerSdkWorkerWithContext(workerId, "us");
        assertTrue(!app.isWorkerOnline(workerId),
                "worker registration alone must not create transport presence");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient client = new ManualAckWebSocketWorkerClient(wsUri, "us", workerId);
        try {
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", "demoApp");
            createBody.put("contract", "SESSION");
            createBody.put("sharedConfig", Map.of(
                    "textContent", "retry redispatch trace observed interactive",
                    "routingCode", "us"
            ));
            createBody.put("userId", "itest");
            createBody.put("sourceRef", "retry-redispatch-interactive");
            createBody.put("executionSpec", Map.of("batchSize", 1));

            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, "demo.dispatch", List.of(Map.of("target", "target-a"))));
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(
                    taskId,
                    snapshot -> "READY".equals(snapshot.task().get("status"))
                            && snapshot.stats().readyCount() == 1
                            && snapshot.activeLeases().isEmpty(),
                    "READY with no active lease while assignment retry waits for transport-online worker",
                    20,
                    250L
            );
            assertEquals(0, readySnapshot.stats().inflightCount());

            assertClientConnects(client, "retry redispatch worker failed to connect");
            assertTrue(awaitCondition(() -> app.isWorkerOnline(workerId), 20, 100L),
                    "worker must become transport-online before retry redispatch can succeed");

            JsonObject dispatch = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(dispatch, "late online worker should receive the retried dispatch");
            client.sendSuccess(dispatch, "retry-redispatch-success");

            RuntimeTaskSnapshot drainedSession = waitForRuntimeTaskSnapshot(taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.stats().readyCount() == 0
                            && snapshot.stats().inflightCount() == 0
                            && snapshot.stats().successCount() == 1
                            && snapshot.stats().finalCount() == 1
                            && snapshot.activeLeases().isEmpty(),
                    "RUNNING session shell with fully drained retry-redispatched work",
                    20,
                    250L);
            assertEquals(1, drainedSession.stats().successCount());
            assertEquals(1, drainedSession.stats().finalCount());
            assertEquals(0, drainedSession.stats().failedCount());
            assertTrue(drainedSession.activeLeases().isEmpty());
            assertEquals("SEALED", String.valueOf(drainedSession.task().get("intakeStatus")));

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "assignment-retry-redispatch",
                    taskId
            );
            assertTrue(trace.eventTypeCounts().containsKey("ASSIGNMENT_RETRY_SCHEDULED"),
                    "canonical trace must show the assignment retry scheduler before redispatch succeeds");
            assertTrue(trace.eventTypeCounts().containsKey("DISPATCH_BINDING_SUMMARY"),
                    "canonical trace must include a successful redispatch binding");
        } finally {
            client.disconnect();
        }
    }
}
