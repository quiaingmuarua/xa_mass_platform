package com.xa.mass.server.e2e.assignment;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ReviewReadModelSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E proof that a worker resource can be created through SDK registration,
 * then run independently through the pull transport.
 */
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
class CrawlerPullWorkerSdkRegistrationIntegrationTest extends ReviewReadModelSampleE2eTest {

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
                rule("crawler-scheduling-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));
        app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId("crawler")
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode("crawler.fetch-page")
                                .projectCodes(List.of("crawlerApp"))
                                .build()
                ))
                .build());
        app.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId("crawler-polling-node")
                .adapterType(WorkerTransportHints.POLLING)
                .endpointId("crawler-polling")
                .build());
        app.bindNodeGroup(NodeGroupBindingRegistration.builder()
                .adapterNodeId("crawler-polling-node")
                .workerGroupId("crawler")
                .build());
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .adapterNodeId("crawler-polling-node")
                .workerGroupId("crawler")
                .transportHint(WorkerTransportHints.POLLING)
                .attributes(Map.of(
                        "type", "crawler",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                ))
                .build());

        assertFalse(app.isWorkerOnline(workerId), "SDK registration must not create transport presence");

        PullWorkerSession session = app.pullWorker(workerId);
        session.connect();
        try {
            waitUntil(() -> app.isWorkerOnline(workerId), "pull session connect must surface transport presence online");
            TaskExecutionOptions executionSpec = new TaskExecutionOptions();
            executionSpec.setBatchSize(1);

            TaskShellSnapshot task = createShellWithOptionalItems(
                    MassTaskShellCreateRequest.builder()
                            .userId("crawler-agent")
                            .project("crawlerApp")
                            .sourceRef("crawler-fetch-page")
                            .sharedConfig(Map.of("routingCode", "us"))
                            .executionSpec(executionSpec)
                            .build(),
                    "crawler.fetch-page",
                    List.of(Map.of("url", "https://example.test/page-1")),
                    false
            );

            assertTrue(app.approveTask(task.getTaskId()));

            List<TaskDispatchItem> items = List.of();
            for (int attempt = 0; attempt < 20 && items.isEmpty(); attempt++) {
                items = session.poll(10);
                if (items.isEmpty()) {
                    Thread.sleep(250L);
                }
            }
            assertFalse(items.isEmpty(), "Expected crawler task dispatch via polling");

            TaskDispatchItem item = items.get(0);
            assertEquals(task.getTaskId(), item.getTaskId());
            assertEquals(workerId, item.getWorkerId());
            assertEquals("https://example.test/page-1", item.getInput().get("url"));

            assertTrue(session.submitResult(
                    item,
                    true,
                    "crawler-success",
                    Map.of("url", "https://example.test/page-1", "statusCode", 200, "title", "Example Page")
            ));

            TaskSnapshot terminal = waitForTerminalTask(task.getTaskId());
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

        waitUntil(() -> !app.isWorkerOnline(workerId), "pull session disconnect must converge transport presence offline");
    }

    private TaskShellSnapshot createShellWithOptionalItems(MassTaskShellCreateRequest request,
                                                           String eventCode,
                                                           List<Object> items,
                                                           boolean keepIntakeOpen) {
        TaskShellSnapshot task = app.createTaskShell(request);
        if (items != null && !items.isEmpty()) {
            app.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(eventCode)
                    .items(items)
                    .build());
        }
        if (!keepIntakeOpen) {
            assertTrue(app.sealTask(task.getTaskId()));
        }
        return task;
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
