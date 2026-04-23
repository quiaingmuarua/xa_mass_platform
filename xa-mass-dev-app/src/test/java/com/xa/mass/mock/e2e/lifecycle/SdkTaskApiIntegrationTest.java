package com.xa.mass.mock.e2e.lifecycle;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mock.client.retry-attempts=1",
                "mock.client.retry-delay=1",
                "mock.client.connection-timeout=5",
                "mock.client.ping-delay=60",
                "mock.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class SdkTaskApiIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTaskThroughUnifiedTaskApiWithSdkCredentialCompletesOverRealRuntime() throws Exception {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-agent")
                .credential("sdk-test-key")
                .userId("sdk-client")
                .projectScope("demoApp")
                .attributes(Map.of("transport", "websocket"))
                .build());

        Map<String, Object> createResponse = exchangeWithHeaders("/status/api/tasks", HttpMethod.POST, Map.of(
                "project", "demoApp",
                "taskName", "sdk-runtime-task",
                "eventCode", "demo.dispatch",
                "mode", "SINGLE_RUN",
                "payloadType", "JSON",
                "sharedConfig", Map.of("source", "sdk"),
                "inputs", java.util.List.of(Map.of("target", "sdk-target-001")),
                "batchSize", 1,
                "defaultMsgMaxRetryCount", 2
        ), Map.of("X-Mass-Api-Key", "sdk-test-key"));

        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertEquals("demoApp", responseData(createResponse).get("project"));
        assertEquals("sdk-client", responseData(createResponse).get("userId"));
        assertEquals("crawler-agent", responseData(createResponse).get("principalId"));

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=sdk",
                HttpMethod.POST,
                null
        );
        assertApiOk(approveResponse);

        TaskSnapshot snapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, snapshot.messages().size());
        assertEquals("SUCCESS", snapshot.messages().get(0).get("status"));

        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detailResponse);
        Map<String, Object> task = task(detailResponse);
        Map<String, Object> sharedConfig = (Map<String, Object>) task.get("sharedConfig");
        Map<String, Object> sdkMetadata = (Map<String, Object>) sharedConfig.get("_sdk");

        assertEquals("demo.dispatch", sdkMetadata.get("eventCode"));
        assertEquals("JSON", sdkMetadata.get("payloadType"));
        assertEquals("SINGLE_RUN", sdkMetadata.get("taskMode"));
        assertTrue(sharedConfig.containsKey("source"));
    }

    @Test
    void createTaskThroughUnifiedTaskApiRejectsSdkSubmitterScopeViolation() {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("telegram-key")
                .userId("bot-user")
                .projectScope("demoApp")
                .build());

        Map<String, Object> createResponse = exchangeWithHeaders("/status/api/tasks", HttpMethod.POST, Map.of(
                "project", "crawlerApp",
                "taskName", "scope-violation",
                "eventCode", "crawler.fetch-page",
                "payloadType", "JSON",
                "inputs", java.util.List.of(Map.of("url", "https://example.test"))
        ), Map.of("Authorization", "Bearer telegram-key"));

        assertApiError(createResponse, 403);
        assertEquals("Submitter project scope does not allow project: crawlerApp", apiMsg(createResponse));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeWithHeaders(String path,
                                                    HttpMethod method,
                                                    Object body,
                                                    Map<String, String> headers) {
        String url = "http://127.0.0.1:" + port + path;
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("Content-Type", "application/json");
        headers.forEach(httpHeaders::set);
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body, httpHeaders), Map.class);
        return response.getBody();
    }
}
