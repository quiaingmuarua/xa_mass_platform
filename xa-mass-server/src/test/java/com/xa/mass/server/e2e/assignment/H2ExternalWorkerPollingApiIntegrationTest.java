package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.engine.model.TaskStateValidationResult;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
@Tag("secondary-proof")
class H2ExternalWorkerPollingApiIntegrationTest extends ProjectionSampleE2eTest {

    /**
     * Support-only storage compatibility coverage.
     *
     * <p>This validates external-worker polling against a JDBC/H2 backend. Keep
     * it out of mainline parity or scheduling proof chains.
     */

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String JDBC_URL = isolatedH2JdbcUrl("external_polling");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registerJdbcStorageProperties(registry, "jdbc-h2", JDBC_URL, "sa", "");
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void externalWorkerPollingApiCompletesTaskEndToEndAgainstJdbcStorage() throws Exception {
        String workerId = "polling-h2-worker-001";
        String workerCredential = "polling-h2-worker-key";
        String submitterCredential = "polling-h2-submitter-key";

        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-scheduling-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerSubmitter(
                "polling-h2-worker",
                workerCredential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("polling-h2-submitter")
                .credential(submitterCredential)
                .userId("crawler-agent")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        HttpHeaders workerHeaders = credentialHeaders(workerCredential);
        HttpHeaders submitterHeaders = credentialHeaders(submitterCredential);
        declareExternalWorkerGroup("polling-jdbc", "crawlerApp", "crawler.fetch-page", workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", "polling-jdbc",
                "attributes", Map.of(
                        "runtime", "jdbc-e2e",
                        "routingTags", "us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("polling", responseData(registerResponse).get("transportHint"));

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST, Map.of(
                "reason", "jdbc-storage-online"
        ), workerHeaders));
        waitUntil(() -> app.isWorkerOnline(workerId), "worker transport presence should be online before task approval");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sourceRef", "crawler-fetch-page-h2");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));
        Map<String, Object> createResponse = createTaskShell(createBody, submitterHeaders);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of("url", "https://example.test/h2-page"))));
        assertApiOk(sealTask(taskId));

        Map<String, Object> createdDetail = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(createdDetail);
        assertEquals("NEW", task(createdDetail).get("status"));
        TaskStateValidationResult createdValidation = validateTaskState(taskId);
        assertTrue(createdValidation.isValid());

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
        assertEquals(taskId, item.get("taskId"));
        assertEquals(workerId, item.get("workerId"));

        Map<String, Object> resultResponse = exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "taskId", item.get("taskId"),
                "messageId", item.get("messageId"),
                "success", true,
                "detail", "jdbc-storage-success",
                "output", Map.of(
                        "url", "https://example.test/h2-page",
                        "statusCode", 200,
                        "title", "H2 JDBC Page"
                )
        ), workerHeaders);
        assertApiOk(resultResponse);
        assertEquals(Boolean.TRUE, responseData(resultResponse).get("submitted"));

        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        assertEquals("SUCCESS", terminal.messages().getFirst().get("status"));
        assertEquals(workerId, terminal.messages().getFirst().get("latestAttemptWorkerId"));

        Map<String, Object> terminalDetail = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        assertApiOk(terminalDetail);
        TaskStateValidationResult terminalValidation = validateTaskState(taskId);
        assertTrue(terminalValidation.isValid());
        assertFalse(terminalValidation.isNeedsResolution());

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST, Map.of(
                "reason", "jdbc-storage-offline"
        ), workerHeaders));
        waitUntil(() -> !app.isWorkerOnline(workerId), "worker transport presence should be offline after explicit disconnect");

        assertJdbcProjection(taskId, workerId);
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

    private void assertJdbcProjection(String taskId, String workerId) throws Exception {
        try (var conn = DriverManager.getConnection(JDBC_URL, "sa", "")) {
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
                    assertFalse(rs.next(), "task_id should remain unique");
                }
            }

            try (var ps = conn.prepareStatement("""
                    SELECT json
                    FROM xa_worker
                    WHERE worker_id = ?
                    """)) {
                ps.setString(1, workerId);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "worker row should exist");
                    assertJsonContains(rs.getString("json"), "\"status\":\"OFFLINE\"");
                    assertFalse(rs.next(), "worker_id should remain unique");
                }
            }

            try (var ps = conn.prepareStatement("SELECT COUNT(*) FROM xa_rule WHERE rule_type = 'QL_EXPRESS'")) {
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }

            try (var ps = conn.prepareStatement("""
                    SELECT principal_type, enabled, json
                    FROM xa_principal
                    WHERE principal_id = ?
                    """)) {
                ps.setString(1, "polling-h2-submitter");
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "principal row should exist");
                    assertEquals("SERVICE", rs.getString("principal_type"));
                    assertTrue(rs.getBoolean("enabled"));
                    assertJsonContains(rs.getString("json"), "\"eventScopes\":[\"crawler.fetch-page\"]");
                    assertFalse(rs.next(), "principal_id should remain unique");
                }
            }

            try (var ps = conn.prepareStatement("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                    WHERE TABLE_NAME IN ('xa_task_msg', 'xa_task_msg_attempt', 'xa_worker_lock')
                    """)) {
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
    }

    private void assertJsonContains(String json, String expected) {
        assertTrue(json != null && json.contains(expected), "expected JSON to contain " + expected + " but was: " + json);
    }
}
