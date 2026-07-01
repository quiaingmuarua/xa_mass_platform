package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
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
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.runtime.mode=redis",
                "mass.transport.polling.buffer.store=redis",
                "mass.transport.endpoint-lease.store=redis",
                "mass.engine.assignment-retry-delay-millis=100",
                "mass.engine.runtime-ready-dispatch-idle-backoff-max-millis=500",
                "mass.engine.lease-watchdog-interval-seconds=1",
                "mass.engine.task-message-lease-seconds=2"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisRuntimeExternalWorkerPollingApiIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String REDIS_HOST = System.getProperty("mass.test.redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("mass.test.redis.port", 6379);
    private static final String REDIS_URI = "redis://" + REDIS_HOST + ":" + REDIS_PORT + "/0";
    private static final String NAMESPACE = "xa:mass:test:redis-polling-runtime:" + UUID.randomUUID();

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("spring.redis.host", () -> REDIS_HOST);
        registry.add("spring.redis.port", () -> REDIS_PORT);
        registry.add("spring.redis.database", () -> 0);
        registry.add("mass.runtime.redis.namespace", () -> NAMESPACE + ":runtime");
        registry.add("mass.transport.polling.buffer.redis.namespace", () -> NAMESPACE + ":polling-delivery");
        registry.add("mass.transport.endpoint-lease.redis.namespace", () -> NAMESPACE + ":endpoint-lease");
    }

    @AfterAll
    static void cleanupRedisNamespace() {
        RedisClient redisClient = RedisClient.create(REDIS_URI);
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            List<String> keys = connection.sync().keys(NAMESPACE + ":*");
            if (!keys.isEmpty()) {
                connection.sync().del(keys.toArray(String[]::new));
            }
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    void externalWorkerPollingApiCompletesTaskEndToEndWithRedisTaskRuntime() throws Exception {
        String workerId = "redis-polling-worker-001";
        String sessionToken = "session-redis-polling-worker-001";
        String workerCredential = "redis-polling-worker-key";
        String taskApiKey = "redis-polling-task-api-key";
        String workerGroupId = "redis-polling-runtime";

        app.replaceDefaultRules(List.of(
                rule("redis-polling-online-project", "supportsProject == true"),
                rule("redis-polling-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        registerExternalWorkerCredential(
                "redis-polling-worker",
                workerCredential,
                workerId,
                "crawlerApp",
                "crawler.fetch-page"
        );
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("redis-polling-task-api")
                .credential(taskApiKey)
                .userId("crawler-agent")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        HttpHeaders workerHeaders = credentialHeaders(workerCredential);
        HttpHeaders taskApiKeyHeaders = credentialHeaders(taskApiKey);
        declareExternalWorkerGroup(workerGroupId, "crawlerApp", "crawler.fetch-page", workerHeaders);
        bindExternalAdapterNode("redis-polling-node", workerGroupId, workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", workerId,
                "workerGroupId", workerGroupId,
                "attributes", Map.of(
                        "runtime", "redis-task-runtime-e2e",
                        "routingTags", "redis,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("polling", responseData(registerResponse).get("transportHint"));
        assertFalse(app.isWorkerReachable(workerId));

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":online", HttpMethod.POST,
                presenceBody(sessionToken, "redis-runtime-polling-online"), workerHeaders));
        waitUntil(() -> app.isWorkerReachable(workerId), "redis polling worker should become reachable");

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
                "items", List.of(Map.of("url", "https://example.test/redis-runtime-page"))
        ), taskApiKeyHeaders));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));

        List<Map<String, Object>> items = List.of();
        for (int attempt = 0; attempt < 10 && items.isEmpty(); attempt++) {
            Map<String, Object> pollResponse = exchange("/worker-api/v1/workers/" + workerId + ":poll", HttpMethod.POST, Map.of(
                    "maxMessages", 10,
                    "timeoutMs", 500
            ), workerHeaders);
            assertApiOk(pollResponse);
            items = pollItems(pollResponse);
        }
        assertFalse(items.isEmpty(), "expected Redis task-runtime dispatch through external polling worker API");

        Map<String, Object> item = items.getFirst();
        assertPollItemHasWorkerActionShape(item);
        assertEquals("crawler.fetch-page", item.get("eventCode"));

        Map<String, Object> resultResponse = exchange("/worker-api/v1/workers/" + workerId + ":submit-result", HttpMethod.POST, Map.of(
                "replyRef", item.get("replyRef"),
                "success", true,
                "body", """
                        {"detail":"redis-runtime-crawler-success","url":"https://example.test/redis-runtime-page","statusCode":200}
                        """.trim()
        ), workerHeaders);
        assertApiOk(resultResponse);
        assertEquals(Boolean.TRUE, responseData(resultResponse).get("submitted"));

        RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
        assertEquals(1, terminal.stats().successCount());
        assertEquals(1, terminal.stats().finalCount());
        assertTrue(terminal.activeLeases().isEmpty());

        assertApiOk(exchange("/worker-api/v1/workers/" + workerId + ":offline", HttpMethod.POST,
                presenceBody(sessionToken, "redis-runtime-polling-offline"), workerHeaders));
        waitUntil(() -> !app.isWorkerReachable(workerId), "redis polling worker should become unreachable");
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
