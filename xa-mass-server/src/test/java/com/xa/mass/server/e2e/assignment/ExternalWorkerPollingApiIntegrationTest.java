package com.xa.mass.server.e2e.assignment;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
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
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class ExternalWorkerPollingApiIntegrationTest extends AbstractMockE2eTest {

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

        HttpHeaders realtimeHeaders = sdkCredentialHeaders("realtime-worker-key");
        HttpHeaders aliasHeaders = sdkCredentialHeaders("alias-worker-key");

        Map<String, Object> realtimeRegisterResponse = exchange("/worker-api/workers/register", HttpMethod.POST, Map.of(
                "workerId", "realtime-worker-001",
                "transportHint", "realtime",
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), realtimeHeaders);
        assertApiError(realtimeRegisterResponse, 400);
        assertTrue(apiMsg(realtimeRegisterResponse).contains(
                "worker adapterId must be set when transportHint 'realtime' is used"));

        Map<String, Object> aliasRegisterResponse = exchange("/worker-api/workers/register", HttpMethod.POST, Map.of(
                "workerId", "realtime-worker-002",
                "transportHint", "websocket",
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
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
                rule("crawler-context-routing", "isWorkerContextAllocatable == true && workerContextMatchesRoutingCode == true")
        ));
        HttpHeaders workerHeaders = sdkCredentialHeaders(credential);
        HttpHeaders submitterHeaders = sdkCredentialHeaders(submitterCredential);

        Map<String, Object> registerResponse = exchange("/worker-api/workers/register", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", "node-runtime",
                "attributes", Map.of("lang", "node"),
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("polling", responseData(registerResponse).get("transportHint"));

        Map<String, Object> contextResponse = exchange("/worker-api/worker-contexts/register", HttpMethod.POST, Map.of(
                "workerContextId", "ctx-" + workerId,
                "workerId", workerId,
                "project", "crawlerApp",
                "routingTags", Set.of("web", "us"),
                "attributes", Map.of("region", "us")
        ), workerHeaders);
        assertApiOk(contextResponse);
        assertFalse(app.isWorkerOnline(workerId), "register must not mark external worker online");

        assertApiOk(exchange("/worker-api/workers/" + workerId + "/online", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "external worker online should update runtime status");

        assertApiOk(exchange("/worker-api/workers/" + workerId + "/heartbeat", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-heartbeat"
        ), workerHeaders));

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", "crawler-fetch-page");
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("eventCode", "crawler.fetch-page");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("inputs", List.of(Map.of("url", "https://example.test/page-1")));
        createBody.put("batchSize", 1);
        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody, submitterHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));

        Map<String, Object> auditResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=external-worker-api",
                HttpMethod.POST,
                null
        );
        assertApiOk(auditResponse);

        Map<String, Object> pollResponse = null;
        List<Map<String, Object>> items = List.of();
        for (int attempt = 0; attempt < 5 && items.isEmpty(); attempt++) {
            pollResponse = exchange("/worker-api/workers/" + workerId + "/poll", HttpMethod.POST, Map.of(
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

        Map<String, Object> resultResponse = exchange("/worker-api/workers/" + workerId + "/results", HttpMethod.POST, Map.of(
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

        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        assertEquals("SUCCESS", terminal.messages().get(0).get("status"));
        assertEquals(workerId, terminal.messages().get(0).get("latestAttemptWorkerId"));
        assertFalse(terminal.messages().get(0).containsKey("latestAttemptId"),
                "latestAttemptId must remain internal and out of status API response");
        assertTrue(terminal.messages().get(0).get("output") instanceof Map);
        Map<?, ?> output = (Map<?, ?>) terminal.messages().get(0).get("output");
        assertEquals("Example Page", output.get("title"));

        assertApiOk(exchange("/worker-api/workers/" + workerId + "/offline", HttpMethod.POST, Map.of(
                "reason", "external-worker-api-offline"
        ), workerHeaders));
        waitUntil(() -> !app.isWorkerOnline(workerId), "external worker offline should update runtime status");
    }

    private HttpHeaders sdkCredentialHeaders(String credential) {
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
                .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .attributes(Map.of("workerId", workerId))
                .build());
    }
}
