package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalNodeWorkerProcess;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class RedisRuntimeNodePollingLateResultReplayBlackBoxIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String FIRST_WORKER_ID = "redis-node-polling-late-001";
    private static final String SECOND_WORKER_ID = "redis-node-polling-current-001";
    private static final String FIRST_WORKER_KEY = "redis-node-polling-late-key";
    private static final String SECOND_WORKER_KEY = "redis-node-polling-current-key";
    private static final String TASK_API_KEY = "redis-node-polling-late-task-key";
    private static final String PROJECT = "crawlerApp";
    private static final String EVENT_CODE = "crawler.fetch-page";
    private static final String WORKER_GROUP_ID = "redis-node-late-result-runtime";
    private static final String REDIS_HOST = System.getProperty("mass.test.redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("mass.test.redis.port", 6379);
    private static final String REDIS_URI = "redis://" + REDIS_HOST + ":" + REDIS_PORT + "/0";
    private static final String NAMESPACE = "xa:mass:test:redis-node-polling-late-result:" + UUID.randomUUID();

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
    void externalNodePollingWorkerLateResultReplayDoesNotOverwriteRetriedRedisFinality() throws Exception {
        registerCredentials();
        app.replaceDefaultRules(List.of(
                rule("redis-node-late-result-online-project", "supportsProject == true"),
                rule("redis-node-late-result-routing", "workerSchedulingMatchesRoutingCode == true")
        ));

        String baseUrl = "http://127.0.0.1:" + port;
        ExternalNodeWorkerProcess firstWorker = ExternalNodeWorkerProcess.startPollingSample(
                baseUrl,
                FIRST_WORKER_ID,
                FIRST_WORKER_KEY,
                WORKER_GROUP_ID,
                Map.of(
                        "MASS_DISPATCH_FAULT", "late-result-after-lease-expiry",
                        "MASS_RESULT_DELAY_MS", "7000"
                ));
        String taskId;
        try {
            waitForWorkerPresenceOnline(
                    FIRST_WORKER_ID,
                    40,
                    250L,
                    () -> firstWorker.assertAlive("First Node polling worker exited before taking the dispatch"),
                    firstWorker::capturedOutput
            );

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("project", PROJECT);
            createBody.put("userId", "crawler-agent");
            createBody.put("sharedConfig", Map.of("routingCode", "us"));
            createBody.put("executionSpec", Map.of(
                    "batchSize", 1,
                    "defaultMaxRetryCount", 1
            ));

            Map<String, Object> createResponse = createTaskShell(createBody, credentialHeaders(TASK_API_KEY));
            assertApiOk(createResponse);
            taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, EVENT_CODE,
                    List.of(Map.of("url", baseUrl + "/api/v1/catalog/events/" + EVENT_CODE))));
            assertApiOk(sealTask(taskId));
            assertApiOk(approveTask(taskId));

            firstWorker.awaitOutputContaining(
                    "fault late-result-after-lease-expiry",
                    Duration.ofSeconds(10),
                    "First Node polling worker did not take the dispatch and schedule a late result replay");
            RuntimeTaskSnapshot firstAttemptActive = waitForRuntimeTaskSnapshot(
                    taskId,
                    snapshot -> snapshot.stats().inflightCount() == 1
                            && snapshot.stats().finalCount() == 0
                            && snapshot.activeLeases().size() == 1,
                    "one active Redis task-runtime lease before the late result replay",
                    12,
                    100L
            );
            assertEquals(FIRST_WORKER_ID, firstAttemptActive.activeLeases().getFirst().workerId());
            assertEquals(0, app.readTaskResults(taskId, 0, 10).getTotalVisible());
            waitForWorkerOffline(FIRST_WORKER_ID, "first late-result worker should be offline while holding only an expired old lease");

            try (ExternalNodeWorkerProcess secondWorker =
                         ExternalNodeWorkerProcess.startPollingSample(baseUrl, SECOND_WORKER_ID, SECOND_WORKER_KEY, WORKER_GROUP_ID)) {
                waitForWorkerPresenceOnline(
                        SECOND_WORKER_ID,
                        40,
                        250L,
                        () -> secondWorker.assertAlive(
                                "Second Node polling worker exited before Redis lease-expiry redispatch"),
                        secondWorker::capturedOutput
                );

                RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
                assertEquals("TERMINAL", terminal.task().get("status"));
                assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
                assertEquals(1, terminal.stats().successCount());
                assertEquals(1, terminal.stats().finalCount());
                assertTrue(terminal.activeLeases().isEmpty());

                TaskResultWindowSnapshot finalWindow = awaitValue(
                        "Task " + taskId + " did not expose the retried Redis-backed final result row",
                        20,
                        100L,
                        () -> app.readTaskResults(taskId, 0, 10),
                        window -> window.getTotalVisible() == 1 && window.getItems().size() == 1,
                        window -> "totalVisible=" + window.getTotalVisible() + ", items=" + window.getItems().size()
                );
                TaskResultItemSnapshot finalItem = finalWindow.getItems().getFirst();
                assertEquals("SUCCESS", finalItem.getStatus());
                assertEquals(SECOND_WORKER_ID, finalItem.getWorkerId());
                assertEquals(1, finalItem.getRetryCount());
                assertNotNull(finalItem.getOutput());
                String resultBody = String.valueOf(finalItem.getOutput().get("result"));
                assertTrue(resultBody.contains("\"workerId\":\"" + SECOND_WORKER_ID + "\""));
                assertTrue(resultBody.contains("\"eventCode\":\"" + EVENT_CODE + "\""));
            }
            waitForWorkerOffline(SECOND_WORKER_ID, "second Node polling worker should go offline after retry proof shutdown");

            firstWorker.awaitOutputContaining(
                    "fault late-result-after-lease-expiry submitted result",
                    Duration.ofSeconds(10),
                    "First Node polling worker did not replay the old result after the retry finality");
            firstWorker.awaitExit(
                    Duration.ofSeconds(5),
                    "First Node polling worker did not exit after submitting the late result replay");

            TaskResultWindowSnapshot finalWindowAfterLateReplay = app.readTaskResults(taskId, 0, 10);
            assertEquals(1, finalWindowAfterLateReplay.getTotalVisible());
            TaskResultItemSnapshot finalItemAfterLateReplay = finalWindowAfterLateReplay.getItems().getFirst();
            assertEquals(SECOND_WORKER_ID, finalItemAfterLateReplay.getWorkerId());
            assertEquals(1, finalItemAfterLateReplay.getRetryCount());
            assertEquals(1, fetchRuntimeTaskSnapshot(taskId).stats().successCount());
            assertEquals(1, fetchRuntimeTaskSnapshot(taskId).stats().finalCount());
        } finally {
            firstWorker.close();
        }
    }

    private void registerCredentials() {
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("redis-node-polling-late-worker")
                .credential(FIRST_WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(PROJECT))
                .eventScopes(List.of(EVENT_CODE))
                .attributes(Map.of("workerId", FIRST_WORKER_ID))
                .build());
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("redis-node-polling-current-worker")
                .credential(SECOND_WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of(PROJECT))
                .eventScopes(List.of(EVENT_CODE))
                .attributes(Map.of("workerId", SECOND_WORKER_ID))
                .build());
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("redis-node-polling-late-task")
                .credential(TASK_API_KEY)
                .userId("crawler-agent")
                .permissions(List.of("task:create"))
                .projectScopes(List.of(PROJECT))
                .eventScopes(List.of(EVENT_CODE))
                .build());
    }

    private HttpHeaders credentialHeaders(String credential) {
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
