package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalJavaWorkerProcess;
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
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class JavaPollingWorkerBlackBoxIntegrationTest extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "java-worker-api-001";
    private static final String WORKER_KEY = "java-worker-key";
    private static final String SUBMITTER_KEY = "java-crawler-submitter-key";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void externalJavaPollingWorkerSampleRegistersCapabilityAndCompletesTask() throws Exception {
        registerSubmitters();
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-scheduling-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));

        String baseUrl = "http://127.0.0.1:" + port;
        try (ExternalJavaWorkerProcess workerProcess =
                     ExternalJavaWorkerProcess.startPollingSample(baseUrl, WORKER_ID, WORKER_KEY)) {
            waitForWorkerPresenceOnline(
                    WORKER_ID,
                    60,
                    250L,
                    () -> workerProcess.assertAlive("External Java polling worker exited before reaching transport-online state"),
                    workerProcess::capturedOutput
            );
            assertWorkerStateProjection(WORKER_ID, "AVAILABLE");
            assertCatalogCapabilityProjection(WORKER_ID);

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", "crawlerApp");
            createBody.put("userId", "java-crawler-agent");
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

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());

            TaskSnapshot terminalView = fetchTaskSnapshot(taskId);
            assertEquals(WORKER_ID, terminalView.messages().get(0).get("latestAttemptWorkerId"));
            Object outputObject = terminalView.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals("cross-language-java-polling", output.get("integrationProbe"));
            assertEquals(WORKER_ID, output.get("workerId"));
            assertEquals("crawler.fetch-page", output.get("eventCode"));
            assertTrue(output.get("statusCode") instanceof Number);
            assertEquals(200, ((Number) output.get("statusCode")).intValue());
            assertTrue(output.containsKey("fetchedAt"));
            assertTrue(output.containsKey("elapsedMs"));

            Object workerProfileObject = output.get("workerProfile");
            assertInstanceOf(Map.class, workerProfileObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
            assertEquals("java-polling-worker", workerProfile.get("runtime"));
            assertEquals(WORKER_ID, workerProfile.get("workerId"));
        }
        waitForWorkerOffline(WORKER_ID, "external Java polling worker should go offline after shutdown");
    }

    private void registerSubmitters() {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("java-crawler-submitter")
                .credential(SUBMITTER_KEY)
                .userId("java-crawler-agent")
                .projectScope("crawlerApp")
                .build());
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("java-polling-worker")
                .credential(WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", WORKER_ID))
                .build());
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
                        && "polling".equals(item.get("adapterId"))
                        && List.of("crawler.fetch-page").equals(item.get("supportedEventCodes"))
                        && hasEventBinding(item, "crawler.fetch-page", "crawlerApp")
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

    @SuppressWarnings("unchecked")
    private boolean hasEventBinding(Map<String, Object> item, String eventCode, String projectCode) {
        Object rawBindings = item.get("eventBindings");
        if (!(rawBindings instanceof List<?> bindings)) {
            return false;
        }
        return bindings.stream().anyMatch(binding -> {
            if (!(binding instanceof Map<?, ?> map)) {
                return false;
            }
            Object projectCodes = map.get("projectCodes");
            return eventCode.equals(map.get("eventCode"))
                    && projectCodes instanceof List<?>
                    && ((List<Object>) projectCodes).contains(projectCode);
        });
    }

    @SuppressWarnings("unchecked")
    private void assertWorkerStateProjection(String workerId, String expectedState) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            Map<String, Object> response = exchange("/api/v1/runtime/workers/" + workerId + "/state", HttpMethod.GET, null);
            assertApiOk(response);
            Object data = response.get("data");
            if (data instanceof Map<?, ?> map && expectedState.equals(map.get("state"))) {
                return;
            }
            Thread.sleep(100L);
        }
        Map<String, Object> response = exchange("/api/v1/runtime/workers/" + workerId + "/state", HttpMethod.GET, null);
        assertApiOk(response);
        Map<String, Object> projection = (Map<String, Object>) response.get("data");
        assertEquals(expectedState, projection.get("state"));
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
