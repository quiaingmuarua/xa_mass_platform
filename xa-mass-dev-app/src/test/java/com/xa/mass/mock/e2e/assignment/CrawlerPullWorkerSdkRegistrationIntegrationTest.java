package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleType;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E proof that a worker resource can be created through SDK registration,
 * then run independently through the pull transport.
 */
@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class CrawlerPullWorkerSdkRegistrationIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void crawlerPullWorkerCreatedViaSdkRegistrationCompletesTaskEndToEnd() throws Exception {
        String workerId = "crawler-worker-001";
        app.replaceDefaultRules(List.of(
                rule("crawler-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("crawler-context-routing", "isWorkerContextAllocatable == true && workerContextMatchesRoutingCode == true")
        ));
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId("crawler")
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode("crawler.fetch-page")
                                .projectCodes(List.of("crawlerApp"))
                                .build()
                ))
                .transportHint(WorkerTransportHints.POLLING)
                .attributes(Map.of("type", "crawler"))
                .build());
        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId("ctx-" + workerId)
                .workerId(workerId)
                .project("crawlerApp")
                .routingTags(Set.of("web", "us"))
                .attributes(Map.of("region", "us"))
                .build());

        assertFalse(app.isWorkerOnline(workerId), "SDK registration must not mark a worker online");

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            waitUntil(() -> app.isWorkerOnline(workerId), "pull session connect must mark the worker online");

            var task = app.createTask(MassTaskRequest.singleRun("crawlerApp", "crawler-fetch-page")
                    .userId("crawler-agent")
                    .eventCode("crawler.fetch-page")
                    .sharedConfig(Map.of("routingCode", "us"))
                    .jsonInputs(List.of(Map.of("url", "https://example.test/page-1")))
                    .batchSize(1)
                    .build());

            assertTrue(app.approveTask(task.getTid()));

            List<TaskDispatchItem> items = List.of();
            for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
                items = session.poll(10);
                if (items.isEmpty()) {
                    Thread.sleep(250L);
                }
            }
            assertFalse(items.isEmpty(), "Expected crawler task dispatch via polling");

            TaskDispatchItem item = items.get(0);
            assertEquals(task.getTid(), item.getTaskId());
            assertEquals(workerId, item.getWorkerId());
            assertEquals("https://example.test/page-1", item.getInput().get("url"));

            assertTrue(session.submitResult(
                    item,
                    true,
                    "crawler-success",
                    Map.of("url", "https://example.test/page-1", "statusCode", 200, "title", "Example Page")
            ));

            TaskSnapshot terminal = waitForTerminalTask(task.getTid());
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals("SUCCESS", terminal.messages().get(0).get("status"));
            assertEquals(workerId, terminal.messages().get(0).get("latestAttemptWorkerId"));
            assertTrue(terminal.messages().get(0).get("output") instanceof Map);
            Map<?, ?> output = (Map<?, ?>) terminal.messages().get(0).get("output");
            assertEquals("https://example.test/page-1", output.get("url"));
            assertEquals(200, ((Number) output.get("statusCode")).intValue());
            assertEquals("Example Page", output.get("title"));
        } finally {
            session.disconnect();
        }

        waitUntil(() -> !app.isWorkerOnline(workerId), "pull session disconnect must mark the worker offline");
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
}
