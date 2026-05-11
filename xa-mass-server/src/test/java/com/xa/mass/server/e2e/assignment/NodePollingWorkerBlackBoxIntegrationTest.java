package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalNodeWorkerProcess;
import com.xa.mass.sdk.MassSdkApplication;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class NodePollingWorkerBlackBoxIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "node-worker-api-001";
    private static final String WORKER_KEY = "node-worker-key";
    private static final String SUBMITTER_KEY = "crawler-submitter-key";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void externalNodePollingWorkerQuickstartScriptRegistersCapabilityAndCompletesTask() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-context-routing", "isWorkerContextAllocatable == true && workerContextMatchesRoutingCode == true")
        ));
        String baseUrl = "http://127.0.0.1:" + port;
        try (ExternalNodeWorkerProcess workerProcess =
                     ExternalNodeWorkerProcess.startPollingSample(baseUrl, WORKER_ID, WORKER_KEY)) {
            waitForWorkerPresenceOnline(
                    WORKER_ID,
                    40,
                    250L,
                    () -> workerProcess.assertAlive("External Node polling worker exited before reaching transport-online state"),
                    workerProcess::capturedOutput
            );
            assertCatalogCapabilityProjection(WORKER_ID);

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", "crawlerApp");
            createBody.put("userId", "crawler-agent");
            createBody.put("sharedConfig", Map.of("routingCode", "us"));
            createBody.put("executionSpec", Map.of("batchSize", 1));

            Map<String, Object> createResponse = createTaskShell(createBody, submitterCredentialHeaders(SUBMITTER_KEY));
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, "crawler.fetch-page",
                    List.of(Map.of("url", baseUrl + "/api/v1/catalog/events/crawler.fetch-page"))));
            assertApiOk(sealTask(taskId));

            Map<String, Object> approveResponse = approveTask(taskId);
            assertApiOk(approveResponse);

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(WORKER_ID, terminal.messages().get(0).get("latestAttemptWorkerId"));

            Object outputObject = terminal.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals(WORKER_ID, output.get("workerId"));
            assertEquals("crawler.fetch-page", output.get("eventCode"));
            assertTrue(output.get("statusCode") instanceof Number);
            assertEquals(200, ((Number) output.get("statusCode")).intValue());
            assertTrue(output.containsKey("fetchedAt"));
            assertTrue(output.containsKey("elapsedMs"));
        }
        waitForWorkerOffline(WORKER_ID, "external node polling worker should go offline after shutdown");
    }

    private void assertCatalogCapabilityProjection(String workerId) {
        Map<String, Object> workerCapabilityResponse = exchange("/api/v1/catalog/worker-capabilities", HttpMethod.GET, null);
        Map<String, Object> eventCapabilityResponse = exchange("/api/v1/catalog/event-capabilities", HttpMethod.GET, null);
        assertApiOk(workerCapabilityResponse);
        assertApiOk(eventCapabilityResponse);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workerCapabilities =
                (List<Map<String, Object>>) workerCapabilityResponse.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eventCapabilities =
                (List<Map<String, Object>>) eventCapabilityResponse.get("data");

        assertNotNull(workerCapabilities);
        assertNotNull(eventCapabilities);
        assertTrue(workerCapabilities.stream().anyMatch(item ->
                workerId.equals(item.get("workerId"))
                        && List.of("crawler.fetch-page").equals(item.get("supportedEventCodes"))
                        && "polling".equals(item.get("transportHint"))
                        && Boolean.TRUE.equals(item.get("online"))
        ));
        assertTrue(eventCapabilities.stream().anyMatch(item ->
                "crawler.fetch-page".equals(item.get("eventCode"))
                        && "TASK_BACKED".equals(item.get("invocationModel"))
                        && item.get("onlineWorkerIds") instanceof List<?>
                        && ((List<?>) item.get("onlineWorkerIds")).contains(workerId)
        ));
    }

    private HttpHeaders submitterCredentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
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
}

