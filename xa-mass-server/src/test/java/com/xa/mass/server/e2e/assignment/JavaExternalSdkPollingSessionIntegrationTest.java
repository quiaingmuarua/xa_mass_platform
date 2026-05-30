package com.xa.mass.server.e2e.assignment;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskContract;
import com.xa.mass.client.task.TaskCreateRequest;
import com.xa.mass.client.task.TaskItemBatch;
import com.xa.mass.client.task.TaskResultReadRequest;
import com.xa.mass.client.task.TaskResultWindow;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.session.PollingWorkerSession;
import com.xa.mass.client.worker.session.WorkerResult;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JavaExternalSdkPollingSessionIntegrationTest extends AbstractSampleE2eTest {
    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void javaExternalSdkPollingSessionCompletesTaskThroughPublicWorkerApi() throws Exception {
        String workerId = "java-sdk-polling-worker-001";
        String workerKey = "java-sdk-polling-worker-key";
        String submitterKey = "java-sdk-task-submitter-key";
        registerTaskSubmitter("java-sdk-task-submitter", submitterKey);
        registerWorkerSubmitter("java-sdk-polling-worker", workerKey, workerId);
        sdkApp().replaceDefaultRules(List.of(
                rule("java-sdk-online-project", "isWorkerAvailable == true && isWorkerLocked == false && supportsProject == true"),
                rule("java-sdk-routing", "isWorkerSchedulingResourceAllocatable == true && workerSchedulingMatchesRoutingCode == true")
        ));

        MassPlatform workerMass = platform(workerKey);
        MassPlatform submitterMass = platform(submitterKey);

        workerMass.workers().declareGroup(WorkerGroupSpec.builder()
                .groupId("java-sdk-phone-probe")
                .bindEvent("crawler.fetch-page", List.of("crawlerApp"))
                .defaultAttribute("region", "sg")
                .defaultMaxConcurrentWork(4)
                .build());

        try (PollingWorkerSession ignored = workerMass.workerSessions().polling()
                .workerId(workerId)
                .workerGroupId("java-sdk-phone-probe")
                .adapterNodeId("java-sdk-poll-node-sg-1")
                .attribute("region", "sg")
                .attribute("country", "sg")
                .attribute("routingTags", "sg")
                .attribute("fingerprint", "fp-android-13-sg")
                .event("crawler.fetch-page", dispatch -> {
                    URI phoneUri = dispatch.input().requiredUri("url");
                    return WorkerResult.success(Map.of(
                            "url", phoneUri.toString(),
                            "mcc", "525",
                            "mnc", "01",
                            "workerId", dispatch.workerId()
                    ));
                })
                .maxMessages(4)
                .pollTimeout(Duration.ofMillis(250))
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofMillis(100))
                .start()) {
            String taskId = submitterMass.tasks().create(TaskCreateRequest.builder()
                    .project("crawlerApp")
                    .userId("java-sdk-agent")
                    .contract(TaskContract.BATCH)
                    .sharedConfig("routingCode", "sg")
                    .executionSpec(com.xa.mass.client.task.TaskExecutionSpec.builder()
                            .batchSize(1)
                            .build())
                    .build()).taskId();
            submitterMass.tasks().appendItems(taskId, TaskItemBatch.builder()
                    .eventCode("crawler.fetch-page")
                    .item(Map.of("url", "tel:+6588880001"))
                    .build());
            submitterMass.tasks().seal(taskId);
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());

            TaskResultWindow results = submitterMass.tasks().results(taskId,
                    TaskResultReadRequest.builder().limit(10).build());
            assertFalse(results.items().isEmpty());
            assertEquals("525", results.items().getFirst().output().get("mcc"));
            assertEquals(workerId, results.items().getFirst().output().get("workerId"));
        }
    }

    private MassPlatform platform(String apiKey) {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey(apiKey)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private void registerTaskSubmitter(String principalId, String credential) {
        sdkApp().registerSubmitter(SubmitterRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());
    }

    private void registerWorkerSubmitter(String principalId, String credential, String workerId) {
        sdkApp().registerSubmitter(SubmitterRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", workerId))
                .build());
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

    private MassSdkApplication sdkApp() {
        if (app == null) {
            throw new IllegalStateException("MassSdkApplication is not available for this E2E fixture");
        }
        return app;
    }
}
