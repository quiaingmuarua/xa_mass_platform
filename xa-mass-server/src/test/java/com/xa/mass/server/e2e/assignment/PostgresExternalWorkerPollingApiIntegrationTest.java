package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.test.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class PostgresExternalWorkerPollingApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String JDBC_URL = EmbeddedPostgresSupport.isolatedJdbcUrl("external_polling");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(
                registry,
                "jdbc-postgres",
                JDBC_URL,
                EmbeddedPostgresSupport.username(),
                EmbeddedPostgresSupport.password()
        );
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void externalWorkerPollingApiCompletesTaskEndToEndAgainstPostgresStorage() throws Exception {
        String workerId = "polling-pg-worker-001";
        String workerCredential = "polling-pg-worker-key";
        String submitterCredential = "polling-pg-submitter-key";

        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-context-routing", "isWorkerContextAllocatable == true && workerContextMatchesRoutingCode == true")
        ));
        registerExternalWorkerSubmitter(
                "polling-pg-worker",
                workerCredential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("polling-pg-submitter")
                .credential(submitterCredential)
                .userId("crawler-agent")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        HttpHeaders workerHeaders = sdkCredentialHeaders(workerCredential);
        HttpHeaders submitterHeaders = sdkCredentialHeaders(submitterCredential);

        Map<String, Object> registerResponse = exchange("/worker-api/workers/register", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", "polling-postgres",
                "attributes", Map.of("runtime", "postgres-e2e"),
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
                "routingTags", Set.of("us"),
                "attributes", Map.of("region", "us")
        ), workerHeaders);
        assertApiOk(contextResponse);

        assertApiOk(exchange("/worker-api/workers/" + workerId + "/online", HttpMethod.POST, Map.of(
                "reason", "postgres-storage-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "worker should be online before task approval");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", "crawler-fetch-page-postgres");
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("eventCode", "crawler.fetch-page");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("inputs", List.of(Map.of("url", "https://example.test/postgres-page")));
        createBody.put("batchSize", 1);
        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody, submitterHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));

        assertApiOk(exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=postgres-jdbc-e2e",
                HttpMethod.POST,
                null
        ));

        List<Map<String, Object>> items = List.of();
        for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/workers/" + workerId + "/poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 1000
            ), workerHeaders);
            assertApiOk(pollResponse);
            items = pollItems(pollResponse);
        }
        assertFalse(items.isEmpty(), "expected polling worker to receive a task item");

        Map<String, Object> item = items.getFirst();
        Map<String, Object> resultResponse = exchange("/worker-api/workers/" + workerId + "/results", HttpMethod.POST, Map.of(
                "taskId", item.get("taskId"),
                "messageId", item.get("messageId"),
                "success", true,
                "detail", "postgres-storage-success",
                "output", Map.of(
                        "url", "https://example.test/postgres-page",
                        "statusCode", 200,
                        "title", "Postgres JDBC Page"
                )
        ), workerHeaders);
        assertApiOk(resultResponse);
        assertEquals(Boolean.TRUE, responseData(resultResponse).get("submitted"));

        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));

        assertApiOk(exchange("/worker-api/workers/" + workerId + "/offline", HttpMethod.POST, Map.of(
                "reason", "postgres-storage-offline"
        ), workerHeaders));
        waitUntil(() -> !app.isWorkerOnline(workerId), "worker should be offline after explicit disconnect");

        assertJdbcProjection(taskId, String.valueOf(item.get("messageId")), workerId);
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
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(projectCode))
                .eventScopes(List.of(eventCode))
                .attributes(Map.of("workerId", workerId))
                .build());
    }

    private void assertJdbcProjection(String taskId, String messageId, String workerId) throws Exception {
        try (var conn = DriverManager.getConnection(
                JDBC_URL,
                EmbeddedPostgresSupport.username(),
                EmbeddedPostgresSupport.password()
        )) {
            try (var ps = conn.prepareStatement("""
                    SELECT status, project, schedulable, json
                    FROM xa_task
                    WHERE task_id = ?
                    """)) {
                ps.setString(1, taskId);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "task row should exist");
                    assertEquals("TERMINAL", rs.getString("status"));
                    assertEquals("crawlerApp", rs.getString("project"));
                    assertFalse(rs.getBoolean("schedulable"));
                    assertJsonContains(rs.getString("json"), "\"terminalReason\":\"ALL_MESSAGES_SUCCEEDED\"");
                }
            }
        }
    }

    private void assertJsonContains(String json, String expected) {
        assertTrue(json != null && json.contains(expected), "expected JSON to contain " + expected + " but was: " + json);
    }
}
