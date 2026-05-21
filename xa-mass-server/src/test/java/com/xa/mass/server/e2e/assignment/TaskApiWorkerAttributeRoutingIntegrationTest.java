package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ManualAckWebSocketWorkerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiWorkerAttributeRoutingIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void routesTaskUsingWorkerSchedulingAttributesCountryLabel() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_scheduling_resource_check", "isWorkerSchedulingResourceAllocatable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("worker_scheduling_attribute_country", "workerSchedulingAttributes['country'] == routingCode")
        ));

        addCandidate("matched-worker", "pool-east", "us", "us");
        addCandidate("other-worker", "pool-west", "us", "gb");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient matchedClient = new ManualAckWebSocketWorkerClient(uri, "matched-worker");
        ManualAckWebSocketWorkerClient otherClient = new ManualAckWebSocketWorkerClient(uri, "other-worker");
        try {
            assertClientConnects(matchedClient, "matched worker client failed to connect");
            assertClientConnects(otherClient, "other worker client failed to connect");

            String taskId = createTaskId("worker-attribute-routing", "attribute routing integration", "target-a");
            Map<String, Object> auditResponse = approveTask(taskId);
            assertApiOk(auditResponse);

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch, "matched worker should receive the routed task");
            assertNull(rejectedDispatch, "worker with mismatched attributes must not receive the task");
            matchedClient.sendSuccess(matchedDispatch, "attribute-routing-ok");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.stats().successCount());
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
                "route-bucket-worker-eu",
                "route-bucket-group",
                "demoApp",
                Map.of("region", "eu")
        );
        registerSdkStatelessWorkerWithAttributes(
                "route-bucket-worker-us",
                "route-bucket-group",
                "demoApp",
                Map.of("region", "us")
        );

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketWorkerClient otherClient = new ManualAckWebSocketWorkerClient(uri, "route-bucket-worker-eu");
        ManualAckWebSocketWorkerClient matchedClient = new ManualAckWebSocketWorkerClient(uri, "route-bucket-worker-us");
        try {
            assertClientConnects(otherClient, "other worker client failed to connect");
            assertClientConnects(matchedClient, "matched worker client failed to connect");

            String taskId = createRouteAttributeTaskId("worker-route-attribute-bucket", "route bucket routing", "target-a");
            assertApiOk(approveTask(taskId));

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch, "approved route attribute bucket should dispatch to matching worker");
            assertNull(rejectedDispatch, "worker outside the approved route attribute bucket must not receive the task");

            matchedClient.sendSuccess(matchedDispatch, "route-attribute-bucket-ok");
            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.stats().successCount());
        } finally {
            matchedClient.disconnect();
            otherClient.disconnect();
        }
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private void addCandidate(String workerId, String workerGroupId, String routingTag, String countryAttribute) {
        registerSdkStatelessWorkerWithAttributes(
                workerId,
                workerGroupId,
                "demoApp",
                Map.of("routingTag", routingTag, "country", countryAttribute)
        );
    }

    private String createRouteAttributeTaskId(String sourceRef, String textContent, String target) {
        Map<String, Object> sharedConfig = new java.util.LinkedHashMap<>();
        sharedConfig.put("textContent", textContent);
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
}
