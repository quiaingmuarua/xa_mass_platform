package com.xa.mass.server.e2e.assignment;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExternalWorkerPollingApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void externalWorkerRegisterApiRequiresExplicitAdapterIdForRealtimeFamily() {
        registerExternalWorkerSubmitter(
                "realtime-worker",
                "realtime-worker-key",
                "realtime-worker-001",
                "crawlerApp",
                "crawler.fetch-page"
        );
        registerExternalWorkerSubmitter(
                "alias-worker",
                "alias-worker-key",
                "realtime-worker-002",
                "crawlerApp",
                "crawler.fetch-page"
        );

        HttpHeaders realtimeHeaders = credentialHeaders("realtime-worker-key");
        HttpHeaders aliasHeaders = credentialHeaders("alias-worker-key");
        declareCrawlerWorkerGroup("realtime-crawler", realtimeHeaders);
        declareCrawlerWorkerGroup("realtime-crawler", aliasHeaders);
        bindAdapterNode("realtime-node", "realtime-crawler", realtimeHeaders);
        bindAdapterNode("alias-node", "realtime-crawler", aliasHeaders);

        Map<String, Object> realtimeRegisterResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", "realtime-worker-001",
                "adapterNodeId", "realtime-node",
                "workerGroupId", "realtime-crawler",
                "transportHint", "realtime"
        ), realtimeHeaders);
        assertApiError(realtimeRegisterResponse, 400);
        assertTrue(apiMsg(realtimeRegisterResponse).contains(
                "worker adapterId must be set when transportHint 'realtime' is used"));

        Map<String, Object> aliasRegisterResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", "realtime-worker-002",
                "adapterNodeId", "alias-node",
                "workerGroupId", "realtime-crawler",
                "transportHint", "websocket"
        ), aliasHeaders);
        assertApiError(aliasRegisterResponse, 400);
        assertTrue(apiMsg(aliasRegisterResponse).contains(
                "External worker API supports only polling or realtime transport"));
    }

    @Test
    void externalWorkerPollingApiCompletesTaskEndToEnd() throws Exception {
        String workerId = "node-worker-api-001";
        String credential = "node-worker-key";
        String submitterCredential = "crawler-submitter-key";
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-scheduling-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders submitterHeaders = credentialHeaders(submitterCredential);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "adapterNodeId", "polling-node",
                "workerGroupId", "node-runtime",
                "attributes", Map.of(
                        "lang", "node",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("polling", responseData(registerResponse).get("transportHint"));

        assertFalse(app.isWorkerOnline(workerId), "registration must not create external worker transport presence");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "external worker online should surface transport presence");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":heartbeat", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-heartbeat"
        ), workerHeaders));

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, submitterHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", List.of(Map.of("url", "https://example.test/page-1"))
        ), submitterHeaders));
        assertApiOk(executeTaskCommand(taskId, "SEAL", null, submitterHeaders));

        Map<String, Object> auditResponse = approveTask(taskId);
        assertApiOk(auditResponse);

        Map<String, Object> pollResponse = null;
        List<Map<String, Object>> items = List.of();
        for (int attempt = 0; attempt < 10 && items.isEmpty(); attempt++) {
            pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 500
            ), workerHeaders);
            assertApiOk(pollResponse);
            items = pollItems(pollResponse);
        }
        assertFalse(items.isEmpty(), "expected task dispatch through external polling worker API");

        Map<String, Object> item = items.get(0);
        assertEquals(taskId, item.get("taskId"));
        assertEquals(workerId, item.get("workerId"));
        assertEquals("crawler.fetch-page", item.get("eventCode"));
        assertFalse(item.containsKey("attemptId"), "attemptId must remain internal and out of worker-api poll response");

        Map<String, Object> resultResponse = exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "taskId", item.get("taskId"),
                "messageId", item.get("messageId"),
                "success", true,
                "detail", "crawler-success",
                "output", Map.of(
                        "url", "https://example.test/page-1",
                        "statusCode", 200,
                        "title", "Example Page"
                )
        ), workerHeaders);
        assertApiOk(resultResponse);
        assertEquals(Boolean.TRUE, responseData(resultResponse).get("submitted"));

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        assertEquals(1, terminal.stats().successCount());

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-offline"
        ), workerHeaders));
        waitUntil(() -> !app.isWorkerOnline(workerId), "external worker offline should converge transport presence");
    }

    @Test
    void externalWorkerPollingApiCanAcknowledgeOperatorIssuedCommand() throws Exception {
        String workerId = "node-worker-api-002";
        String credential = "node-worker-ack-key";
        String submitterCredential = "crawler-submitter-key";
        registerExternalWorkerSubmitter(
                "node-worker-ack",
                credential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders submitterHeaders = credentialHeaders(submitterCredential);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "adapterNodeId", "polling-node",
                "workerGroupId", "node-runtime",
                "attributes", Map.of(
                        "lang", "node",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST, Map.of(
                "reason", "command-ack-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "command-ack worker should reach transport-online state");

        Map<String, Object> commandResponse = exchange("/api/v1/runtime/workers/" + workerId + "/commands", HttpMethod.POST, Map.of(
                "commandId", "cmd-node-worker-ack-001",
                "commandType", "DRAIN",
                "requester", "ops-admin",
                "reason", "maintenance",
                "idempotencyKey", "idem-node-worker-ack-001",
                "payload", Map.of("mode", "soft")
        ));
        assertApiOk(commandResponse);
        @SuppressWarnings("unchecked")
        Map<String, Object> command = (Map<String, Object>) responseData(commandResponse).get("command");
        assertNotNull(command);
        assertEquals("REQUESTED", command.get("status"));

        Map<String, Object> commandPollResponse = exchange(
                "/worker-api/v1/workers/" + workerId + "/commands:poll",
                HttpMethod.POST,
                Map.of("maxCommands", 5),
                workerHeaders
        );
        assertApiOk(commandPollResponse);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> polledCommands =
                (List<Map<String, Object>>) responseData(commandPollResponse).get("commands");
        assertEquals(1, polledCommands.size());
        assertEquals("cmd-node-worker-ack-001", polledCommands.getFirst().get("commandId"));
        assertEquals("DELIVERY_ACCEPTED", polledCommands.getFirst().get("status"));

        Map<String, Object> ackResponse = exchange(
                "/worker-api/v1/workers/" + workerId + "/commands/cmd-node-worker-ack-001:ack",
                HttpMethod.POST,
                Map.of(
                        "status", "DELIVERY_ACCEPTED",
                        "reason", "accepted"
                ),
                workerHeaders
        );
        assertApiOk(ackResponse);
        assertEquals("DELIVERY_ACCEPTED", responseData(ackResponse).get("currentStatus"));

        Map<String, Object> readResponse = exchange("/api/v1/runtime/workers/commands/cmd-node-worker-ack-001", HttpMethod.GET, null);
        assertApiOk(readResponse);
        @SuppressWarnings("unchecked")
        Map<String, Object> readCommand = (Map<String, Object>) readResponse.get("data");
        assertEquals(workerId, readCommand.get("workerId"));
        assertEquals("DELIVERY_ACCEPTED", readCommand.get("status"));
        assertEquals("command pulled by worker", readCommand.get("statusReason"));

        String taskId = createReadyCrawlerTask(submitterHeaders);
        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 250
            ), workerHeaders);
            assertApiOk(pollResponse);
            assertTrue(pollItems(pollResponse).isEmpty(), "drain command acknowledgement must stop new dispatches");
            Thread.sleep(150L);
        }
        waitForRuntimeTaskSnapshot(
                taskId,
                snapshot -> "READY".equals(snapshot.task().get("status")) || "RUNNING".equals(snapshot.task().get("status")),
                "READY or RUNNING while drained worker stops new dispatches",
                8,
                200L
        );

        Map<String, Object> failedAckResponse = exchange(
                "/worker-api/v1/workers/" + workerId + "/commands/cmd-node-worker-ack-001:ack",
                HttpMethod.POST,
                Map.of(
                        "status", "FAILED",
                        "reason", "worker failed to finish drain flow"
                ),
                workerHeaders
        );
        assertApiOk(failedAckResponse);
        assertEquals("FAILED", responseData(failedAckResponse).get("currentStatus"));

        String stillDrainedTaskId = createReadyCrawlerTask(submitterHeaders);
        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 250
            ), workerHeaders);
            assertApiOk(pollResponse);
            assertTrue(pollItems(pollResponse).isEmpty(), "failed drain command must not re-enable dispatch without AVAILABLE");
            Thread.sleep(150L);
        }
        waitForRuntimeTaskSnapshot(
                stillDrainedTaskId,
                snapshot -> "READY".equals(snapshot.task().get("status")) || "RUNNING".equals(snapshot.task().get("status")),
                "READY or RUNNING while failed drain command keeps dispatch disabled",
                8,
                200L
        );

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-state", HttpMethod.POST, Map.of(
                "state", "AVAILABLE",
                "reason", "manual resume"
        ), workerHeaders));

        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> resumedPoll = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 250
            ), workerHeaders);
            assertApiOk(resumedPoll);
            assertTrue(pollItems(resumedPoll).isEmpty(),
                    "AVAILABLE state must not clear WORKER_COMMAND drain source");
            Thread.sleep(150L);
        }
        waitForRuntimeTaskSnapshot(
                stillDrainedTaskId,
                snapshot -> "READY".equals(snapshot.task().get("status")) || "RUNNING".equals(snapshot.task().get("status")),
                "READY or RUNNING while command drain source remains active",
                8,
                200L
        );
    }

    @Test
    void drainingStateStopsNewAssignmentsUntilWorkerReportsAvailable() throws Exception {
        String workerId = "node-worker-draining-001";
        String credential = "node-worker-draining-key";
        String submitterCredential = "crawler-submitter-key";
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-scheduling-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerSubmitter(
                "node-worker-draining",
                credential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders submitterHeaders = credentialHeaders(submitterCredential);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "adapterNodeId", "polling-node",
                "workerGroupId", "node-runtime",
                "attributes", Map.of(
                        "lang", "node",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders));
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST, Map.of(
                "reason", "draining-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "draining worker should reach transport-online state");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-state", HttpMethod.POST, Map.of(
                "state", "DRAINING",
                "reason", "maintenance"
        ), workerHeaders));

        String taskId = createReadyCrawlerTask(submitterHeaders);

        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 250
            ), workerHeaders);
            assertApiOk(pollResponse);
            assertTrue(pollItems(pollResponse).isEmpty(), "draining worker must not receive new work");
            Thread.sleep(150L);
        }

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-state", HttpMethod.POST, Map.of(
                "state", "AVAILABLE",
                "reason", "ready"
        ), workerHeaders));

        Map<String, Object> resumedPoll = null;
        List<Map<String, Object>> resumedItems = List.of();
        for (int attempt = 0; attempt < 8 && resumedItems.isEmpty(); attempt++) {
            resumedPoll = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 500
            ), workerHeaders);
            assertApiOk(resumedPoll);
            resumedItems = pollItems(resumedPoll);
        }
        assertFalse(resumedItems.isEmpty(), "worker should receive new work after reporting AVAILABLE");
        Map<String, Object> item = resumedItems.getFirst();
        assertEquals(taskId, item.get("taskId"));
        assertEquals(workerId, item.get("workerId"));

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "taskId", item.get("taskId"),
                "messageId", item.get("messageId"),
                "success", true,
                "detail", "draining-resumed-success",
                "output", Map.of("workerId", workerId)
        ), workerHeaders));

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pollItems(Map<String, Object> response) {
        Object items = responseData(response).get("items");
        return items instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setName(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        rule.setEnabled(true);
        return rule;
    }

    private void declareCrawlerWorkerGroup(String groupId, HttpHeaders workerHeaders) {
        assertApiOk(exchange("/worker-api/v1/worker-groups", HttpMethod.POST, Map.of(
                "groupId", groupId,
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), workerHeaders));
    }

    private void bindAdapterNode(String adapterNodeId, String workerGroupId, HttpHeaders workerHeaders) {
        assertApiOk(exchange("/worker-api/v1/adapter-nodes", HttpMethod.POST, Map.of(
                "adapterNodeId", adapterNodeId,
                "adapterType", "polling",
                "endpointId", adapterNodeId
        ), workerHeaders));
        assertApiOk(exchange("/worker-api/v1/node-group-bindings", HttpMethod.POST, Map.of(
                "adapterNodeId", adapterNodeId,
                "workerGroupId", workerGroupId
        ), workerHeaders));
    }

    private String createReadyCrawlerTask(HttpHeaders submitterHeaders) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, submitterHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", List.of(Map.of("url", "https://example.test/draining"))
        ), submitterHeaders));
        assertApiOk(executeTaskCommand(taskId, "SEAL", null, submitterHeaders));
        assertApiOk(approveTask(taskId));
        return taskId;
    }

    private void submitSuccessfulWorkerResult(String workerId,
                                              HttpHeaders workerHeaders,
                                              Map<String, Object> item,
                                              String detail) {
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "taskId", item.get("taskId"),
                "messageId", item.get("messageId"),
                "success", true,
                "detail", detail,
                "output", Map.of("workerId", workerId)
        ), workerHeaders));
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

    private void registerExternalWorkerSubmitter(String principalId,
                                                 String credential,
                                                 String workerId,
                                                 String projectCode,
                                                 String eventCode) {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .attributes(Map.of("workerId", workerId))
                .build());
    }
}
