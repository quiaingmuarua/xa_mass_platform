package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void routeAttributesNarrowStageOneCandidatesBeforeRuleAdmission() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true")
        ));

        registerSdkStatelessWorkerWithAttributes(
                "route-bucket-trace-worker-eu",
                "route-bucket-trace-group",
                "demoApp",
                Map.of("region", "eu")
        );
        registerSdkStatelessWorkerWithAttributes(
                "route-bucket-trace-worker-us",
                "route-bucket-trace-group",
                "demoApp",
                Map.of("region", "us")
        );

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient otherClient = new ManualAckWebSocketWorkerClient(uri, "route-bucket-trace-worker-eu");
        ManualAckWebSocketWorkerClient matchedClient = new ManualAckWebSocketWorkerClient(uri, "route-bucket-trace-worker-us");
        try {
            assertClientConnects(otherClient, "other route bucket worker client failed to connect");
            assertClientConnects(matchedClient, "matched route bucket worker client failed to connect");

            String taskId = createRouteAttributeTaskId(
                    "worker-route-attribute-bucket-trace",
                    "route bucket trace observed",
                    "target-a"
            );
            assertApiOk(approveTask(taskId));

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch,
                    "approved route attribute bucket should dispatch to matching worker");
            assertNull(rejectedDispatch,
                    "worker outside the approved route attribute bucket must not receive the task");

            TraceAnalyzeResponse groupTrace = awaitTraceScenarioOk(
                    new TraceOperatorService(),
                    "group-capability-routing",
                    taskId
            );
            assertTrue(groupTrace.ok(), groupTrace.issues().toString());
            assertEquals(1L, groupTrace.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));

            matchedClient.sendSuccess(matchedDispatch, "route-attribute-bucket-trace-ok");
            assertEquals("ALL_MESSAGES_SUCCEEDED", waitForTerminalRuntimeTask(taskId).task().get("terminalReason"));
        } finally {
            matchedClient.disconnect();
            otherClient.disconnect();
        }
    }

    @Test
    void phoneDeviceFingerprintMatchHappensInsideSelectedWorkerGroup() throws Exception {
        registerDeviceProbeCatalog();
        app.replaceDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("worker_fingerprint_match",
                        "workerSchedulingAttributes['fingerprintProfile'] == taskSharedConfig['requiredFingerprintProfile']")
        ));

        registerSdkStatelessWorkerWithAttributes(
                "phone-device-worker-fp-a",
                "phone-device-probe",
                "deviceProbe",
                Map.of(
                        "fingerprintProfile", "fp-android-sg-a",
                        "networkOperatorMccMnc", "52501",
                        "deviceModel", "Pixel-7a",
                        "country", "SG"
                )
        );
        registerSdkStatelessWorkerWithAttributes(
                "phone-device-worker-fp-b",
                "phone-device-probe",
                "deviceProbe",
                Map.of(
                        "fingerprintProfile", "fp-android-sg-b",
                        "networkOperatorMccMnc", "52505",
                        "deviceModel", "Galaxy-A54",
                        "country", "SG"
                )
        );

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient matchedClient =
                new ManualAckWebSocketWorkerClient(uri, "phone-device-worker-fp-a");
        ManualAckWebSocketWorkerClient otherClient =
                new ManualAckWebSocketWorkerClient(uri, "phone-device-worker-fp-b");
        try {
            assertClientConnects(matchedClient, "matched phone device worker client failed to connect");
            assertClientConnects(otherClient, "other phone device worker client failed to connect");

            String taskId = createPhoneMetadataTaskId(
                    "phone-fingerprint-stage-two-proof",
                    "phone fingerprint stage two proof",
                    "+6591234567",
                    "fp-android-sg-a"
            );
            assertApiOk(approveTask(taskId));

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch,
                    "worker with the required fingerprint must receive the phone metadata probe item");
            assertNull(rejectedDispatch,
                    "same-group worker with a different fingerprint must not receive the item");

            TraceAnalyzeResponse groupTrace = awaitTraceScenarioOk(
                    new TraceOperatorService(),
                    "group-capability-routing",
                    taskId
            );
            assertTrue(groupTrace.ok(), groupTrace.issues().toString());
            assertEquals(1L, groupTrace.eventTypeCounts().get("WORKER_MATCH_ACCEPTED"));

            matchedClient.sendSuccess(matchedDispatch,
                    "probe.phone.metadata classified=VALID_E164 fingerprintProfile=fp-android-sg-a");
            assertEquals("ALL_MESSAGES_SUCCEEDED", waitForTerminalRuntimeTask(taskId).task().get("terminalReason"));
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
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_IDS, List.of("pool-east", "pool-west"));
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

    private String createRouteAttributeTaskId(String sourceRef, String textContent, String target) {
        Map<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        sharedConfig.put("textContent", textContent);
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, "route-bucket-trace-group");
        sharedConfig.put(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of("region", "us"));
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

    private void registerDeviceProbeCatalog() {
        app.registerEventDefinition(EventDefinition.builder()
                .code("probe.phone.metadata")
                .name("Phone Metadata Probe")
                .description("Validate phone number metadata and carrier hints without public network access.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(List.of("deviceProbe"))
                .build());
        app.registerProject(ProjectDefinition.builder()
                .code("deviceProbe")
                .name("Device Probe")
                .description("Device and phone metadata probe fixtures.")
                .eventCodes(List.of("probe.phone.metadata"))
                .build());
    }

    private String createPhoneMetadataTaskId(String sourceRef,
                                             String textContent,
                                             String phoneNumber,
                                             String requiredFingerprintProfile) {
        Map<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        sharedConfig.put("textContent", textContent);
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, "phone-device-probe");
        sharedConfig.put("requiredFingerprintProfile", requiredFingerprintProfile);
        sharedConfig.put("requiredNetworkOperatorMccMnc", "52501");
        sharedConfig.put(TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "probe.phone.metadata"));

        Map<String, Object> createBody = new java.util.LinkedHashMap<>();
        createBody.put("project", "deviceProbe");
        createBody.put("sharedConfig", sharedConfig);
        createBody.put("userId", "itest");
        createBody.put("sourceRef", sourceRef);
        createBody.put("executionSpec", Map.of("batchSize", 1));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        assertApiOk(appendTaskItems(taskId, "probe.phone.metadata", List.of(Map.of(
                "phoneNumber", phoneNumber,
                "defaultRegion", "SG",
                "workerGroupId", "phone-device-probe",
                "requiredFingerprintProfile", requiredFingerprintProfile,
                "requiredNetworkOperatorMccMnc", "52501",
                "sleepMs", 80,
                "timeoutMs", 3000,
                "expectedOutcome", "VALID_E164",
                "traceLabel", "stage2-phone-fingerprint-proof"
        ))));
        assertApiOk(sealTask(taskId));
        return taskId;
    }
}
