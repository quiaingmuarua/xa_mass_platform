package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.e2e.support.ManualAckWebSocketWorkerClient;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceOperatorService;
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
class TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("worker-attribute-routing-trace-observed");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
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
        ManualAckWebSocketWorkerClient matchedClient = new ManualAckWebSocketWorkerClient(uri, "attribute-routing-worker-us");
        ManualAckWebSocketWorkerClient otherClient = new ManualAckWebSocketWorkerClient(uri, "attribute-routing-worker-gb");
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
        return awaitTraceScenarioOk(TRACE_OUTPUT_DIR, scenarioId, taskId);
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
}
