package com.xa.mass.server.e2e.assignment;

import com.google.gson.JsonObject;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.testutil.WsFrameTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
class TaskApiWorkerContextAttributeRoutingIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void routesTaskUsingWorkerContextAttributesCountryLabel() throws Exception {
        app.replaceDefaultRules(List.of(
                rule("basic_worker_check", "isWorkerAvailable == true && isWorkerLocked == false"),
                rule("worker_context_status_check", "isWorkerContextAllocatable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("worker_context_attribute_country", "workerContextAttributes['country'] == routingCode")
        ));

        addCandidate("matched-worker", "pool-east", "us", "us");
        addCandidate("other-worker", "pool-west", "us", "gb");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient matchedClient = new ManualAckWebSocketClient(uri, "matched-worker");
        ManualAckWebSocketClient otherClient = new ManualAckWebSocketClient(uri, "other-worker");
        try {
            assertClientConnects(matchedClient, "matched worker client failed to connect");
            assertClientConnects(otherClient, "other worker client failed to connect");

            String taskId = createTaskId("worker-context-attribute-routing", "attribute routing integration", "target-a");
            Map<String, Object> auditResponse = exchange(
                    "/api/v1/tasks/" + taskId + ":approve",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(auditResponse);

            JsonObject matchedDispatch = matchedClient.awaitTask(3, TimeUnit.SECONDS);
            JsonObject rejectedDispatch = otherClient.awaitTask(300, TimeUnit.MILLISECONDS);
            assertNotNull(matchedDispatch, "matched worker should receive the routed task");
            assertNull(rejectedDispatch, "worker with mismatched context attributes must not receive the task");
            matchedClient.sendSuccess(matchedDispatch, "attribute-routing-ok");

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.stats().successCount());
        } finally {
            otherClient.disconnect();
            matchedClient.disconnect();
        }
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private void addCandidate(String workerId, String workerGroupId, String routingTag, String countryAttribute) {
        registerSdkWorkerWithContext(
                workerId,
                workerGroupId,
                routingTag,
                "demoApp",
                Map.of("country", countryAttribute)
        );
    }

    private static final class ManualAckWebSocketClient extends SampleWorkerWebSocketClient {
        private final BlockingQueue<JsonObject> taskQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonObject frame = WsFrameTestSupport.parse(message);
                if (frame != null && WsFrameTestSupport.isTask(frame) && !WsFrameTestSupport.isResponse(frame)) {
                    taskQueue.offer(frame);
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private JsonObject awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private void sendSuccess(JsonObject taskMessage, String detail) throws Exception {
            sendMessage(WsFrameTestSupport.buildTaskResult(
                    WsFrameTestSupport.messageId(taskMessage),
                    WsFrameTestSupport.project(taskMessage),
                    getWorkerId(),
                    WsFrameTestSupport.taskId(taskMessage),
                    "SUCCESS",
                    detail
            ));
        }
    }
}

