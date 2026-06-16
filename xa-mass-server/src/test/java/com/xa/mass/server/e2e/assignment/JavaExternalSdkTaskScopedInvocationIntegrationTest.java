package com.xa.mass.server.e2e.assignment;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskCommandResult;
import com.xa.mass.client.task.TaskHandle;
import com.xa.mass.client.task.TaskResultReadRequest;
import com.xa.mass.client.task.TaskResultWindow;
import com.xa.mass.client.task.TaskSyncAppendResult;
import com.xa.mass.contract.task.TaskContract;
import com.xa.mass.contract.task.TaskCreateRequest;
import com.xa.mass.contract.task.TaskExecutionSpec;
import com.xa.mass.contract.task.TaskItemSyncRequest;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.handler.WorkerResult;
import com.xa.mass.client.worker.session.PollingWorkerSession;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
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
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JavaExternalSdkTaskScopedInvocationIntegrationTest extends AbstractSampleE2eTest {
    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void javaExternalSdkTaskHandleApprovesSessionAndRunsSyncAppendThroughPublicApis() throws Exception {
        String workerId = "java-sdk-task-handle-worker-001";
        String workerKey = "java-sdk-task-handle-worker-key";
        String taskApiKey = "java-sdk-task-handle-api-key-key";
        registerTaskApiKey("java-sdk-task-handle-api-key", taskApiKey);
        registerWorkerApiKey("java-sdk-task-handle-worker", workerKey, workerId);
        sdkApp().replaceDefaultRules(List.of(
                rule("java-sdk-task-handle-online", "supportsProject == true"),
                rule("java-sdk-task-handle-routing", "workerSchedulingMatchesRoutingCode == true")
        ));

        MassPlatform workerMass = platform(workerKey);
        MassPlatform taskApiClient = platform(taskApiKey);

        workerMass.workers().declareGroup(WorkerGroupSpec.builder()
                .groupId("java-sdk-task-handle-phone-probe")
                .bindEvent("crawler.fetch-page", List.of("crawlerApp"))
                .defaultAttribute("region", "sg")
                .defaultMaxConcurrentWork(4)
                .build());

        try (PollingWorkerSession ignored = workerMass.workerSessions().polling()
                .workerId(workerId)
                .workerGroupId("java-sdk-task-handle-phone-probe")
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
                .maxMessages(2)
                .pollTimeout(Duration.ofMillis(250))
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofMillis(100))
                .start()) {
            String taskId = taskApiClient.tasks().create(TaskCreateRequest.builder()
                    .project("crawlerApp")
                    .userId("java-sdk-task-handle-agent")
                    .contract(TaskContract.SESSION)
                    .workerGroupId("java-sdk-task-handle-phone-probe")
                    .routingCode("sg")
                    .executionSpec(TaskExecutionSpec.builder()
                            .workloadClass("INTERACTIVE")
                            .batchSize(1)
                            .foreground(true)
                            .build())
                    .build()).taskId();

            TaskHandle handle = taskApiClient.tasks().forTask(taskId);
            TaskCommandResult approve = handle.approve();
            assertTrue(approve.accepted());
            assertEquals("READY", approve.status());
            assertEquals("OPEN", approve.intakeStatus());

            TaskSyncAppendResult sync = handle.appendItemSync(TaskItemSyncRequest.builder()
                    .eventCode("crawler.fetch-page")
                    .item(Map.of("url", "tel:+6588880001"))
                    .timeoutMs(10_000L)
                    .build());
            assertTrue(sync.synced());
            assertFalse(sync.timedOut());
            assertEquals("SUCCESS", sync.status());
            assertEquals("525", sync.output().get("mcc"));
            assertEquals(workerId, sync.output().get("workerId"));

            TaskResultWindow results = handle.results(TaskResultReadRequest.builder().limit(10).build());
            assertFalse(results.items().isEmpty());
            assertEquals("525", results.items().getFirst().output().get("mcc"));
            assertEquals(workerId, results.items().getFirst().workerId());
        }
    }

    private MassPlatform platform(String apiKey) {
        return MassPlatform.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey(apiKey)
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private void registerTaskApiKey(String principalId, String credential) {
        sdkApp().registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.TASK_CREATE_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());
    }

    private void registerWorkerApiKey(String principalId, String credential, String workerId) {
        sdkApp().registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
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
