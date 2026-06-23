package com.xa.mass.server.e2e.assignment;

import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
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
@ActiveProfiles("memory-local")
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
    void externalWorkerRegisterApiAcceptsRealtimeTransportHintWithoutAdapterNodeId() {
        registerExternalWorkerCredential(
                "realtime-worker",
                "realtime-worker-key",
                "realtime-worker-001",
                "crawlerApp",
                "crawler.fetch-page"
        );
        registerExternalWorkerCredential(
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
                "workerGroupId", "realtime-crawler",
                "transportHint", "realtime"
        ), realtimeHeaders);
        assertApiOk(realtimeRegisterResponse);
        assertFalse(responseData(realtimeRegisterResponse).containsKey("adapterId"));

        Map<String, Object> aliasRegisterResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", "realtime-worker-002",
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
        String sessionToken = "session-node-worker-api-001";
        String credential = "node-worker-key";
        String taskApiKey = "crawler-task-api-key";
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "supportsProject == true"),
                rule("crawler-scheduling-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
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

        assertFalse(app.isWorkerReachable(workerId), "registration must not create external worker transport presence");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST,
                presenceBody(sessionToken, "external-worker-api-online"), workerHeaders));
        waitUntil(() -> app.isWorkerReachable(workerId), "external worker online should surface transport presence");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":heartbeat", HttpMethod.POST,
                presenceBody(sessionToken, "external-worker-api-heartbeat"), workerHeaders));

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST,
                presenceBody("stale-" + sessionToken, "stale-offline-must-not-win"), workerHeaders));
        assertTrue(app.isWorkerReachable(workerId), "stale session token must not revoke current transport presence");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, taskApiKeyHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", List.of(Map.of("url", "https://example.test/page-1"))
        ), taskApiKeyHeaders));
        assertApiOk(sealTask(taskId));

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
        assertPollItemHasWorkerActionShape(item);
        assertEquals("crawler.fetch-page", item.get("eventCode"));

        Map<String, Object> resultResponse = exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "replyRef", item.get("replyRef"),
                "success", true,
                "body", """
                        {"detail":"crawler-success","url":"https://example.test/page-1","statusCode":200,"title":"Example Page"}
                        """.trim()
        ), workerHeaders);
        assertApiOk(resultResponse);
        assertEquals(Boolean.TRUE, responseData(resultResponse).get("submitted"));

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        assertEquals(1, terminal.stats().successCount());

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST,
                presenceBody(sessionToken, "external-worker-api-offline"), workerHeaders));
        waitUntil(() -> !app.isWorkerReachable(workerId), "external worker offline should converge transport presence");
    }

    @Test
    void pollingWorkersSharingRouteAndQueueCannotCrossConsumeSelectedWorkerItems() throws Exception {
        String selectedWorkerId = "shared-polling-worker-us";
        String otherWorkerId = "shared-polling-worker-eu";
        String selectedWorkerCredential = "shared-polling-worker-us-key";
        String otherWorkerCredential = "shared-polling-worker-eu-key";
        String workerGroupId = "shared-polling-route";
        String adapterNodeId = "shared-polling-node";
        String taskApiKey = "crawler-task-api-key";
        app.replaceDefaultRules(List.of(
                rule("shared-polling-online-project", "supportsProject == true"),
                rule("shared-polling-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerCredential(
                "shared-polling-worker-us-principal",
                selectedWorkerCredential,
                selectedWorkerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        registerExternalWorkerCredential(
                "shared-polling-worker-eu-principal",
                otherWorkerCredential,
                otherWorkerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        HttpHeaders selectedWorkerHeaders = credentialHeaders(selectedWorkerCredential);
        HttpHeaders otherWorkerHeaders = credentialHeaders(otherWorkerCredential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareCrawlerWorkerGroup(workerGroupId, selectedWorkerHeaders);
        bindAdapterNode(adapterNodeId, workerGroupId, selectedWorkerHeaders);

        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", selectedWorkerId,
                "workerGroupId", workerGroupId,
                "attributes", Map.of(
                        "routingTags", "shared,us",
                        "country", "us",
                        "region", "us"
                )
        ), selectedWorkerHeaders));
        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", otherWorkerId,
                "workerGroupId", workerGroupId,
                "attributes", Map.of(
                        "routingTags", "shared,eu",
                        "country", "eu",
                        "region", "eu"
                )
        ), otherWorkerHeaders));
        assertApiOk(exchange("/worker-api/v1/workers/" + selectedWorkerId + ":online", HttpMethod.POST,
                presenceBody("session-" + selectedWorkerId, "shared-route-selected-online"), selectedWorkerHeaders));
        assertApiOk(exchange("/worker-api/v1/workers/" + otherWorkerId + ":online", HttpMethod.POST,
                presenceBody("session-" + otherWorkerId, "shared-route-other-online"), otherWorkerHeaders));
        waitUntil(
                () -> app.isWorkerReachable(selectedWorkerId) && app.isWorkerReachable(otherWorkerId),
                "both shared-route polling workers should reach transport presence");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, taskApiKeyHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", List.of(Map.of("url", "https://example.test/shared-route"))
        ), taskApiKeyHeaders));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));

        RuntimeTaskSnapshot running = waitForRuntimeTaskSnapshot(
                taskId,
                snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                        && !snapshot.activeLeases().isEmpty()
                        && selectedWorkerId.equals(snapshot.activeLeases().getFirst().workerId()),
                "RUNNING with selected worker " + selectedWorkerId,
                20,
                250L);
        assertEquals(selectedWorkerId, running.activeLeases().getFirst().workerId());

        Map<String, Object> otherPollResponse = exchange("/worker-api/v1/workers/" + otherWorkerId + ":poll",
                HttpMethod.POST,
                Map.of(
                        "maxMessages", 10,
                        "timeoutMs", 500
                ),
                otherWorkerHeaders);
        assertApiOk(otherPollResponse);
        assertTrue(pollItems(otherPollResponse).isEmpty(),
                "worker sharing routeKey and deliveryQueueKey must not consume another selectedWorkerId item");

        Map<String, Object> selectedPollResponse = null;
        List<Map<String, Object>> selectedItems = List.of();
        for (int attempt = 0; attempt < 8 && selectedItems.isEmpty(); attempt++) {
            selectedPollResponse = exchange("/worker-api/v1/workers/" + selectedWorkerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 500
            ), selectedWorkerHeaders);
            assertApiOk(selectedPollResponse);
            selectedItems = pollItems(selectedPollResponse);
        }
        assertFalse(selectedItems.isEmpty(), "selected polling worker should receive its own assigned item");

        Map<String, Object> item = selectedItems.getFirst();
        assertEquals(selectedWorkerId, responseData(selectedPollResponse).get("workerId"));
        assertPollItemHasWorkerActionShape(item);
        submitSuccessfulWorkerActionReply(selectedWorkerId, selectedWorkerHeaders, item, "shared-route-selected-worker-success");

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
    }

    @Test
    void externalWorkerPollingApiCanAcknowledgeOperatorIssuedCommand() throws Exception {
        String workerId = "node-worker-api-002";
        String sessionToken = "session-node-worker-api-002";
        String credential = "node-worker-ack-key";
        String taskApiKey = "crawler-task-api-key";
        registerExternalWorkerCredential(
                "node-worker-ack",
                credential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", "node-runtime",
                "attributes", Map.of(
                        "lang", "node",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST,
                presenceBody(sessionToken, "command-ack-online"), workerHeaders));
        waitUntil(() -> app.isWorkerReachable(workerId), "command-ack worker should reach transport-online state");

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

        String taskId = createReadyCrawlerTask(taskApiKeyHeaders);
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

        String stillDrainedTaskId = createReadyCrawlerTask(taskApiKeyHeaders);
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

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-runtime-evidence", HttpMethod.POST, Map.of(
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
        String sessionToken = "session-node-worker-draining-001";
        String credential = "node-worker-draining-key";
        String taskApiKey = "crawler-task-api-key";
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "supportsProject == true"),
                rule("crawler-scheduling-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerCredential(
                "node-worker-draining",
                credential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        HttpHeaders workerHeaders = credentialHeaders(credential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareCrawlerWorkerGroup("node-runtime", workerHeaders);
        bindAdapterNode("polling-node", "node-runtime", workerHeaders);

        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", "node-runtime",
                "attributes", Map.of(
                        "lang", "node",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders));
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST,
                presenceBody(sessionToken, "draining-online"), workerHeaders));
        waitUntil(() -> app.isWorkerReachable(workerId), "draining worker should reach transport-online state");

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-runtime-evidence", HttpMethod.POST, Map.of(
                "state", "DRAINING",
                "reason", "maintenance"
        ), workerHeaders));

        String taskId = createReadyCrawlerTask(taskApiKeyHeaders);

        for (int attempt = 0; attempt < 4; attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 250
            ), workerHeaders);
            assertApiOk(pollResponse);
            assertTrue(pollItems(pollResponse).isEmpty(), "draining worker must not receive new work");
            Thread.sleep(150L);
        }

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":report-runtime-evidence", HttpMethod.POST, Map.of(
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
        assertPollItemHasWorkerActionShape(item);

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "replyRef", item.get("replyRef"),
                "success", true,
                "body", "{\"detail\":\"draining-resumed-success\",\"workerId\":\"" + workerId + "\"}"
        ), workerHeaders));

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }

    private Map<String, Object> presenceBody(String sessionToken, String reason) {
        return Map.of(
                "sessionToken", sessionToken,
                "reason", reason
        );
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
                "adapterType", adapterTypeForNode(adapterNodeId),
                "endpointId", adapterNodeId
        ), workerHeaders));
        assertApiOk(exchange("/worker-api/v1/node-group-bindings", HttpMethod.POST, Map.of(
                "adapterNodeId", adapterNodeId,
                "workerGroupId", workerGroupId
        ), workerHeaders));
    }

    private String adapterTypeForNode(String adapterNodeId) {
        String normalized = adapterNodeId == null ? "" : adapterNodeId.toLowerCase();
        if (normalized.contains("realtime") || normalized.contains("websocket")) {
            return "websocket";
        }
        if (normalized.contains("socket")) {
            return "socket";
        }
        return "polling";
    }

    private String createReadyCrawlerTask(HttpHeaders taskApiKeyHeaders) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, taskApiKeyHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", List.of(Map.of("url", "https://example.test/draining"))
        ), taskApiKeyHeaders));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));
        return taskId;
    }

    private void submitSuccessfulWorkerActionReply(String workerId,
                                                   HttpHeaders workerHeaders,
                                                   Map<String, Object> item,
                                                   String detail) {
        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "replyRef", item.get("replyRef"),
                "success", true,
                "body", "{\"detail\":\"" + detail + "\",\"workerId\":\"" + workerId + "\"}"
        ), workerHeaders));
    }

    private static void assertPollItemHasWorkerActionShape(Map<String, Object> item) {
        assertFalse(item.containsKey("workerId"), "poll item must not carry worker identity; worker is bound by poll path");
        assertFalse(item.containsKey("taskId"));
        assertFalse(item.containsKey("messageId"));
        assertFalse(item.containsKey("attemptId"));
        assertFalse(item.containsKey("attemptNo"));
        assertFalse(item.containsKey("batchId"));
        assertNotNull(item.get("actionId"));
        assertNotNull(item.get("replyRef"));
        assertNotNull(item.get("eventCode"));
        assertTrue(item.get("body") instanceof String);
        assertTrue(item.get("sharedConfig") instanceof Map<?, ?>);
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

    private void registerExternalWorkerCredential(String principalId,
                                                 String credential,
                                                 String workerId,
                                                 String projectCode,
                                                 String eventCode) {
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .attributes(Map.of("workerId", workerId))
                .build());
    }
}
