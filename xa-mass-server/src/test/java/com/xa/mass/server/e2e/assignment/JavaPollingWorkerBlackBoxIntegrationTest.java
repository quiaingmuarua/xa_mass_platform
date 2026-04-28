package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalJavaWorkerProcess;
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
import java.util.function.BooleanSupplier;

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
class JavaPollingWorkerBlackBoxIntegrationTest extends AbstractSampleE2eTest {

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
                rule("crawler-context-routing", "isWorkerContextAllocatable == true && workerContextMatchesRoutingCode == true")
        ));

        String baseUrl = "http://127.0.0.1:" + port;
        try (ExternalJavaWorkerProcess workerProcess =
                     ExternalJavaWorkerProcess.startPollingSample(baseUrl, WORKER_ID, WORKER_KEY)) {
            waitForWorkerOnline(WORKER_ID, workerProcess);
            assertSdkMetadataProjection(WORKER_ID);

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("taskName", "external-java-polling-worker");
            createBody.put("project", "crawlerApp");
            createBody.put("userId", "java-crawler-agent");
            createBody.put("eventCode", "crawler.fetch-page");
            createBody.put("sharedConfig", Map.of("routingCode", "us"));
            createBody.put("inputs", List.of(Map.of("url", baseUrl + "/sdk/meta/events/crawler.fetch-page")));
            createBody.put("batchSize", 1);

            Map<String, Object> createResponse = exchange(
                    "/status/api/tasks",
                    HttpMethod.POST,
                    createBody,
                    sdkCredentialHeaders(SUBMITTER_KEY)
            );
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=java-polling-black-box",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(approveResponse);

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(WORKER_ID, terminal.messages().get(0).get("latestAttemptWorkerId"));

            Object outputObject = terminal.messages().get(0).get("output");
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
        waitUntil(() -> !app.isWorkerOnline(WORKER_ID), "external Java polling worker should go offline after shutdown");
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
                .permissions(List.of(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", WORKER_ID))
                .build());
    }

    private void assertSdkMetadataProjection(String workerId) {
        Map<String, Object> workerCapabilityResponse = exchange("/sdk/meta/worker-capabilities", HttpMethod.GET, null);
        Map<String, Object> eventCapabilityResponse = exchange("/sdk/meta/event-capabilities", HttpMethod.GET, null);
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

    private HttpHeaders sdkCredentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }

    private void waitForWorkerOnline(String workerId,
                                     ExternalJavaWorkerProcess workerProcess) throws InterruptedException {
        Worker latestWorker = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            workerProcess.assertAlive("External Java polling worker exited before reaching ONLINE");
            latestWorker = app.getAllWorkers().stream()
                    .filter(worker -> workerId.equals(worker.getWorkerId()))
                    .findFirst()
                    .orElse(null);
            if (latestWorker != null
                    && latestWorker.getStatus() != null
                    && "ONLINE".equals(latestWorker.getStatus().name())) {
                return;
            }
            Thread.sleep(250L);
        }

        workerProcess.assertAlive("External Java polling worker exited while waiting for ONLINE");
        assertNotNull(latestWorker, "Worker should have been registered in runtime");
        throw new AssertionError("Worker " + workerId + " did not reach ONLINE"
                + ". Last runtime worker=" + latestWorker
                + "\nJava worker output:\n" + workerProcess.capturedOutput());
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
}
