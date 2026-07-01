package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.e2e.support.ExternalNodeWorkerProcess;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.runtime.mode=redis",
                "mass.transport.endpoint-lease.store=redis",
                "mass.trace.sink.enabled=true",
                "mass.trace.sink.queue-capacity=256",
                "mass.trace.sink.rotate-after-lines=1",
                "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                "mass.trace.sink.shutdown-drain-timeout-ms=1500",
                "mass.engine.assignment-retry-delay-millis=100",
                "mass.engine.runtime-ready-dispatch-idle-backoff-max-millis=500",
                "mass.engine.lease-watchdog-interval-seconds=1",
                "mass.engine.task-message-lease-seconds=2"
        }
)
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RedisRuntimeNodeWebSocketWorkerBlackBoxIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("redis-node-websocket-runtime-trace-observed");
    private static final String WORKER_ID = "redis-node-websocket-worker-001";
    private static final String WORKER_KEY = "redis-node-websocket-worker-key";
    private static final String WORKER_GROUP_ID = "redis-node-websocket-crawler";
    private static final String ADAPTER_NODE_ID = "redis-node-websocket-node";
    private static final String REDIS_HOST = System.getProperty("mass.test.redis.host", "127.0.0.1");
    private static final int REDIS_PORT = Integer.getInteger("mass.test.redis.port", 6379);
    private static final String REDIS_URI = "redis://" + REDIS_HOST + ":" + REDIS_PORT + "/0";
    private static final String NAMESPACE = "xa:mass:test:redis-node-websocket-runtime:" + UUID.randomUUID();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("spring.redis.host", () -> REDIS_HOST);
        registry.add("spring.redis.port", () -> REDIS_PORT);
        registry.add("spring.redis.database", () -> 0);
        registry.add("mass.runtime.redis.namespace", () -> NAMESPACE + ":runtime");
        registry.add("mass.transport.endpoint-lease.redis.namespace", () -> NAMESPACE + ":endpoint-lease");
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
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
    void externalNodeWebSocketWorkerCompletesTaskThroughRedisTaskRuntimeServingLane() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("redis-node-websocket-online-project", "supportsProject == true"),
                rule("redis-node-websocket-routing", "workerSchedulingMatchesRoutingCode == true")
        ));
        app.registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId("redis-node-websocket-worker")
                .credential(WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", WORKER_ID))
                .build());
        HttpHeaders workerHeaders = credentialHeaders(WORKER_KEY);
        declareExternalWorkerGroup(WORKER_GROUP_ID, "crawlerApp", "crawler.fetch-page", workerHeaders);
        bindExternalAdapterNode(ADAPTER_NODE_ID, WORKER_GROUP_ID, workerHeaders);

        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", WORKER_ID,
                "workerGroupId", WORKER_GROUP_ID,
                "transportHint", "realtime",
                "attributes", Map.of(
                        "lang", "node",
                        "runtime", "node-websocket-worker",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertFalse(app.isWorkerReachable(WORKER_ID),
                "control-plane registration must not create realtime transport presence");

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "crawler-agent");
        createBody.put("sourceRef", "redis-node-websocket-worker");
        createBody.put("sharedConfig", Map.of("routingCode", "us"));
        createBody.put("executionSpec", Map.of("batchSize", 1));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page",
                List.of(Map.of("url", "https://example.test/redis-node-websocket"))));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));

        RuntimeTaskSnapshot readyWhileOffline = waitForRuntimeTaskSnapshot(taskId, "READY", 10, 200L);
        assertEquals(1, readyWhileOffline.stats().readyCount());
        assertTrue(readyWhileOffline.activeLeases().isEmpty());

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        try (ExternalNodeWorkerProcess worker = ExternalNodeWorkerProcess.startWebSocketSample(
                WORKER_ID,
                WORKER_GROUP_ID,
                wsUri
        )) {
            waitForWorkerPresenceOnline(
                    WORKER_ID,
                    30,
                    250L,
                    () -> worker.assertAlive("External Node WebSocket worker exited before reaching ONLINE"),
                    worker::capturedOutput
            );

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());

            TaskResultWindowSnapshot finalWindow = awaitValue(
                    "Task " + taskId + " did not expose one Redis-backed WebSocket final result row",
                    20,
                    100L,
                    () -> app.readTaskResults(taskId, 0, 10),
                    window -> window.getTotalVisible() == 1 && window.getItems().size() == 1,
                    window -> "totalVisible=" + window.getTotalVisible() + ", items=" + window.getItems().size()
            );
            TaskResultItemSnapshot finalItem = finalWindow.getItems().getFirst();
            assertEquals("SUCCESS", finalItem.getStatus());
            assertEquals(WORKER_ID, finalItem.getWorkerId());
            Map<String, Object> finalOutput = finalItem.getOutput();
            assertNotNull(finalOutput);
            String resultBody = String.valueOf(finalOutput.get("result"));
            assertTrue(resultBody.contains("\"integrationProbe\":\"cross-language-node\""));
            assertTrue(resultBody.contains("\"eventCode\":\"crawler.fetch-page\""));
            assertTrue(resultBody.contains("\"url\":\"https://example.test/redis-node-websocket\""));
            assertTrue(resultBody.contains("\"runtime\":\"node-websocket-worker\""));
            assertTrue(resultBody.contains("\"workerId\":\"" + WORKER_ID + "\""));

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "task-runtime-external-worker-success",
                    taskId + "," + WORKER_ID
            );
            assertTrue(trace.eventTypeCounts().containsKey("DISPATCH_BINDING_SUMMARY"),
                    "canonical trace must include task-runtime dispatch binding for Redis Node WebSocket worker");
            assertTrue(trace.eventTypeCounts().containsKey("TASK_TERMINAL_CLOSED"),
                    "canonical trace must include terminal convergence for Redis Node WebSocket worker");
        }
        waitForWorkerOffline(WORKER_ID, "realtime websocket worker should go offline after Redis proof disconnect");
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
