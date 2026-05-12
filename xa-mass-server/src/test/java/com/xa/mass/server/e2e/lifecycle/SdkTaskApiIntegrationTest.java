package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
                "mass.mock.bootstrap.enabled=true",
                "mass.mock.bootstrap.register-dev-catalog=true",
                "mass.mock.bootstrap.register-dev-submitters=false",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "sample.client.retry-attempts=1",
                "sample.client.retry-delay=1",
                "sample.client.connection-timeout=5",
                "sample.client.ping-delay=60",
                "sample.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class SdkTaskApiIntegrationTest extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createTaskThroughUnifiedTaskApiWithSubmitterCredentialCompletesOverRealRuntime() throws Exception {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-agent")
                .credential("sdk-test-key")
                .userId("sdk-client")
                .projectScope("demoApp")
                .attributes(Map.of("transport", "websocket"))
                .build());

        Map<String, Object> createResponse = createTaskShell(Map.of(
                "project", "demoApp",
                "sharedConfig", Map.of("source", "submitter"),
                "executionSpec", Map.of("batchSize", 1)
        ), submitterCredentialHeaders(Map.of("X-Mass-Api-Key", "sdk-test-key")));

        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertEquals("demoApp", responseData(createResponse).get("project"));
        assertEquals("sdk-client", responseData(createResponse).get("userId"));
        assertEquals("crawler-agent", responseData(createResponse).get("principalId"));
        assertApiOk(appendTaskItems(taskId, "demo.dispatch", java.util.List.of(Map.of("target", "sdk-target-001"))));
        assertApiOk(sealTask(taskId));

        Map<String, Object> approveResponse = approveTask(taskId);
        assertApiOk(approveResponse);

        TaskSnapshot snapshot = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, snapshot.messages().size());
        assertEquals("SUCCESS", snapshot.messages().get(0).get("status"));

        Map<String, Object> detailResponse = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(detailResponse);
        Map<String, Object> task = task(detailResponse);
        Map<String, Object> securityView = (Map<String, Object>) responseData(detailResponse).get("security");
        Map<String, Object> sharedConfig = (Map<String, Object>) task.get("sharedConfig");
        assertTrue(sharedConfig.containsKey("source"));
        assertFalse(sharedConfig.containsKey("_massSecurity"));
        assertEquals("crawler-agent", securityView.get("createdByPrincipalId"));
        assertEquals("SERVICE", securityView.get("createdByPrincipalType"));

        Map<String, Object> listResponse = exchange("/api/v1/tasks", HttpMethod.GET, null,
                submitterCredentialHeaders(Map.of("X-Mass-Api-Key", "sdk-test-key")));
        assertApiOk(listResponse);
        List<Map<String, Object>> items = (List<Map<String, Object>>) responseData(listResponse).get("items");
        assertTrue(items.stream().anyMatch(item -> taskId.equals(String.valueOf(item.get("id")))));
    }

    @Test
    void createTaskThroughUnifiedTaskApiRejectsSubmitterScopeViolation() {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("telegram-key")
                .userId("bot-user")
                .projectScope("demoApp")
                .build());

        Map<String, Object> createResponse = createTaskShell(Map.of(
                "project", "crawlerApp",
                "userId", "bot-user"
        ), submitterCredentialHeaders(Map.of("Authorization", "Bearer telegram-key")));

        assertApiError(createResponse, 403);
        assertEquals("Submitter credential project scope denied: crawlerApp", apiMsg(createResponse));
    }

    @Test
    void appendTaskThroughUnifiedTaskApiRejectsSubmitterEventScopeViolation() {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-reader")
                .credential("crawler-reader-key")
                .userId("crawler-user")
                .projectScopes(java.util.List.of("crawlerApp"))
                .eventScopes(java.util.List.of("crawler.parse-result"))
                .build());

        Map<String, Object> createResponse = createTaskShell(Map.of(
                "project", "crawlerApp",
                "userId", "crawler-user"
        ), submitterCredentialHeaders(Map.of("X-Mass-Api-Key", "crawler-reader-key")));

        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));

        Map<String, Object> appendResponse = exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "eventCode", "crawler.fetch-page",
                "items", java.util.List.of(Map.of("url", "https://example.test/page-1"))
        ), submitterCredentialHeaders(Map.of("X-Mass-Api-Key", "crawler-reader-key")));

        assertApiError(appendResponse, 403);
        assertEquals("Submitter credential event scope denied: crawler.fetch-page", apiMsg(appendResponse));
    }

    @SuppressWarnings("unchecked")
    private HttpHeaders submitterCredentialHeaders(Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("Content-Type", "application/json");
        headers.forEach(httpHeaders::set);
        return httpHeaders;
    }
}
