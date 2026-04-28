package com.xa.mass.server.e2e.assignment;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.MockWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
class TaskApiWorkerContextAttributeRoutingIntegrationTest extends AbstractMockE2eTest {

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
        MockWorkerWebSocketClient matchedClient = new MockWorkerWebSocketClient(uri, "matched-worker");
        MockWorkerWebSocketClient otherClient = new MockWorkerWebSocketClient(uri, "other-worker");
        try {
            assertClientConnects(matchedClient, "matched worker client failed to connect");
            assertClientConnects(otherClient, "other worker client failed to connect");

            String taskId = createTaskId("worker-context-attribute-routing", "attribute routing integration", "target-a");
            Map<String, Object> auditResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=worker-context-attribute-routing",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(auditResponse);

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.messages().size());

            Map<String, Object> message = terminalSnapshot.messages().get(0);
            assertEquals("matched-worker", message.get("latestAttemptWorkerId"));
            assertEquals("worker-context-matched-worker", message.get("latestAttemptWorkerContextId"));
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("latestAttemptBatchId"));
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
}
