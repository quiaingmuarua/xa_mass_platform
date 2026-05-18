package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = Path.of(
            "target",
            "worker-attribute-routing-trace-observed",
            UUID.randomUUID().toString()
    ).toAbsolutePath().normalize();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.trace.sink.output-dir", () -> TRACE_OUTPUT_DIR.toString());
    }

    @Test
    void workerAttributeRoutingIsObservedWithoutWorkerContextEvidence() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("worker_scheduling_attribute_country", "workerSchedulingAttributes['country'] == routingCode")
        ));

        registerSdkStatelessWorkerWithAttributes(
                "attribute-routing-worker-us",
                "pool-east",
                "demoApp",
                Map.of("routingTags", "shared,us", "country", "us")
        );
        registerSdkStatelessWorkerWithAttributes(
                "attribute-routing-worker-gb",
                "pool-west",
                "demoApp",
                Map.of("routingTags", "shared,gb", "country", "gb")
        );

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient matchedClient = new ManualAckWebSocketClient(uri, "attribute-routing-worker-us");
        ManualAckWebSocketClient otherClient = new ManualAckWebSocketClient(uri, "attribute-routing-worker-gb");
        try {
            assertClientConnects(matchedClient, "matched worker client failed to connect");
            assertClientConnects(otherClient, "other worker client failed to connect");

            String taskId = createSdkEventTaskId(
                    "worker-attribute-routing-trace",
                    "attribute routing trace observed",
                    "target-a"
            );
            assertApiOk(approveTask(taskId));

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch, "matched stateless worker should receive routed task");
            assertEquals("attribute-routing-worker-us", WsFrameTestSupport.workerId(matchedDispatch));
            assertTrue(rejectedDispatch == null, "mismatched worker must not receive routed task");

            TraceOperatorService traceOperator = new TraceOperatorService();
            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    traceOperator,
                    "worker-attribute-routing-without-context",
                    taskId
            );
            assertTrue(trace.ok(), trace.issues().toString());
            assertEquals("worker-attribute-routing-without-context", trace.scenarioId());
            assertEquals(1L, trace.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));

            TraceAnalyzeResponse groupTrace = awaitTraceScenarioOk(
                    traceOperator,
                    "group-capability-routing",
                    taskId
            );
            assertTrue(groupTrace.ok(), groupTrace.issues().toString());
            assertEquals("group-capability-routing", groupTrace.scenarioId());
            assertEquals(1L, groupTrace.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));

            matchedClient.sendSuccess(matchedDispatch, "attribute-routing-trace-ok");
            assertEquals("ALL_MESSAGES_SUCCEEDED", waitForTerminalRuntimeTask(taskId).task().get("terminalReason"));

            TraceAnalyzeResponse cleanupTrace = awaitTraceScenarioOk(
                    traceOperator,
                    "worker-resource-cleanup-without-context",
                    taskId
            );
            assertTrue(cleanupTrace.ok(), cleanupTrace.issues().toString());
            assertEquals("worker-resource-cleanup-without-context", cleanupTrace.scenarioId());
            assertTrue(cleanupTrace.eventTypeCounts().containsKey("RESOURCE_RELEASED"),
                    "canonical trace must include worker-level RESOURCE_RELEASED");
        } finally {
            otherClient.disconnect();
            matchedClient.disconnect();
        }
    }

    private TraceAnalyzeResponse awaitTraceScenarioOk(TraceOperatorService traceOperator,
                                                      String scenarioId,
                                                      String taskId)
            throws InterruptedException {
        TraceAnalyzeResponse latestResponse = null;
        Exception latestException = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                latestResponse = traceOperator.analyze(new TraceAnalyzeRequest(
                        TRACE_OUTPUT_DIR.toString(),
                        scenarioId,
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

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private String createSdkEventTaskId(String sourceRef, String textContent, String target) {
        Map<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        sharedConfig.put("textContent", textContent);
        sharedConfig.put(TaskSharedConfig.ROUTING_CODE, "us");
        sharedConfig.put(TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "demo.dispatch"));

        Map<String, Object> createBody = new java.util.LinkedHashMap<>();
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", sharedConfig);
        createBody.put("userId", "itest");
        createBody.put("sourceRef", sourceRef);
        createBody.put("executionSpec", Map.of("batchSize", 1));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        assertApiOk(appendTaskItems(taskId, "demo.dispatch", List.of(Map.of("target", target))));
        assertApiOk(sealTask(taskId));
        return taskId;
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId) {
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
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}
