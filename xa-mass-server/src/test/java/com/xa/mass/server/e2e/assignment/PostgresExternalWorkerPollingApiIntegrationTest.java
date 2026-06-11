package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ReviewReadModelSampleE2eTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
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
@DirtiesContext
@Tag("secondary-proof")
class PostgresExternalWorkerPollingApiIntegrationTest extends ReviewReadModelSampleE2eTest {

    /**
     * Support-only storage compatibility coverage.
     *
     * <p>This validates external-worker polling against the PostgreSQL-backed
     * shell. It is not mainline parity proof ownership.
     */

    private static final int WEBSOCKET_PORT = findFreePort();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(
                registry,
                "jdbc-postgres",
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void externalWorkerPollingApiCompletesTaskEndToEndAgainstPostgresStorage() throws Exception {
        String workerId = "polling-pg-worker-001";
        String sessionToken = "session-polling-pg-worker-001";
        String workerCredential = "polling-pg-worker-key";
        String taskApiKey = "polling-pg-api-key-key";

        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "supportsProject == true"),
                rule("crawler-scheduling-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerCredential(
                "polling-pg-worker",
                workerCredential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("polling-pg-api-key")
                .credential(taskApiKey)
                .userId("crawler-agent")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        HttpHeaders workerHeaders = credentialHeaders(workerCredential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareExternalWorkerGroup("polling-postgres", "crawlerApp", "crawler.fetch-page", workerHeaders);
        bindExternalAdapterNode("polling-postgres-node", "polling-postgres", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "adapterNodeId", "polling-postgres-node",
                "workerGroupId", "polling-postgres",
                "attributes", Map.of(
                        "runtime", "postgres-e2e",
                        "routingTags", "us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("polling", responseData(registerResponse).get("transportHint"));

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST,
                presenceBody(sessionToken, "postgres-storage-online"), workerHeaders));
        waitUntil(() -> app.isWorkerReachable(workerId), "worker transport presence should be online before task approval");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sourceRef", "crawler-fetch-page-postgres");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, taskApiKeyHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of("url", "https://example.test/postgres-page"))));
        assertApiOk(sealTask(taskId));

        assertApiOk(approveTask(taskId));

        List<Map<String, Object>> items = List.of();
        for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 1000
            ), workerHeaders);
            assertApiOk(pollResponse);
            items = pollItems(pollResponse);
        }
        assertFalse(items.isEmpty(), "expected polling worker to receive a task item");

        Map<String, Object> item = items.getFirst();
        Map<String, Object> resultResponse = exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
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

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST,
                presenceBody(sessionToken, "postgres-storage-offline"), workerHeaders));
        waitUntil(() -> !app.isWorkerReachable(workerId), "worker transport presence should be offline after explicit disconnect");

        assertJdbcProjection(taskId, String.valueOf(item.get("messageId")), workerId);
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

    private void assertJdbcProjection(String taskId, String messageId, String workerId) throws Exception {
        try (var conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
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
