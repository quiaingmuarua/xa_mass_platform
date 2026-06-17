package com.xa.mass.server.e2e.assignment;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskResultReadRequest;
import com.xa.mass.client.task.TaskResultWindow;
import com.xa.mass.contract.task.TaskContract;
import com.xa.mass.contract.task.TaskCreateRequest;
import com.xa.mass.contract.task.TaskExecutionSpec;
import com.xa.mass.contract.task.TaskItemBatch;
import com.xa.mass.client.worker.session.PollingWorkerSession;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.kernel.spi.rule.RuleType;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.workerpack.tool.geo.GeoLookupTool;
import com.xa.mass.workerpack.tool.geo.GeoLookupWorkerPack;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
@ActiveProfiles("memory-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkerPackGeoLookupExternalSdkIntegrationTest extends AbstractSampleE2eTest {
    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void workerPackGeoLookupRegistersExternallyAndCompletesTaskThroughJavaSdk() throws Exception {
        String workerId = "worker-pack-geo-worker-001";
        String workerKey = "worker-pack-geo-worker-key";
        String taskApiKey = "worker-pack-geo-api-key-key";
        registerWorkerPackCatalogFixture();
        registerTaskApiKey("worker-pack-geo-api-key", taskApiKey);
        registerWorkerApiKey("worker-pack-geo-worker", workerKey, workerId);
        sdkApp().replaceDefaultRules(List.of(
                rule("worker-pack-geo-online", "hasWorkerSchedulingResource == true"),
                rule("worker-pack-geo-routing", "workerSchedulingMatchesRoutingCode == true")
        ));

        MassPlatform workerMass = platform(workerKey);
        MassPlatform taskApiClient = platform(taskApiKey);

        try (PollingWorkerSession ignored = GeoLookupWorkerPack.builder(workerMass)
                .workerId(workerId)
                .projectCodes(List.of("workerPackApp"))
                .attribute("routingTags", "global")
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofMillis(100))
                .startPolling()) {
            String taskId = taskApiClient.tasks().create(TaskCreateRequest.builder()
                    .project("workerPackApp")
                    .userId("worker-pack-agent")
                    .contract(TaskContract.BATCH)
                    .sharedConfig("routingCode", "global")
                    .executionSpec(TaskExecutionSpec.builder()
                            .batchSize(1)
                            .build())
                    .build()).taskId();
            taskApiClient.tasks().appendItems(taskId, TaskItemBatch.builder()
                    .eventCode(GeoLookupTool.EVENT_CODE)
                    .item(Map.of("query", "Beijing"))
                    .build());
            taskApiClient.tasks().seal(taskId);
            assertApiOk(approveTask(taskId));

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());

            TaskResultWindow results = taskApiClient.tasks().results(taskId,
                    TaskResultReadRequest.builder().limit(10).build());
            assertFalse(results.items().isEmpty());
            assertEquals("CN", results.items().getFirst().output().get("countryCode"));
            assertEquals("CNY", results.items().getFirst().output().get("currency"));
            assertEquals(workerId, results.items().getFirst().workerId());
        }
    }

    private void registerWorkerPackCatalogFixture() {
        sdkApp().registerEventDefinition(EventDefinition.builder()
                .code(GeoLookupTool.EVENT_CODE)
                .name("Worker Pack Geo Lookup")
                .description("Resolve a city or location query through the worker-pack geo tool.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("workerPackApp"))
                .build());
        sdkApp().registerProject(ProjectDefinition.builder()
                .code("workerPackApp")
                .name("Worker Pack App")
                .description("Worker-pack SDK capability proof project.")
                .eventCodes(List.of(GeoLookupTool.EVENT_CODE))
                .build());
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
                .projectScopes(List.of("workerPackApp"))
                .eventScopes(List.of(GeoLookupTool.EVENT_CODE))
                .build());
    }

    private void registerWorkerApiKey(String principalId, String credential, String workerId) {
        sdkApp().registerCredentialPrincipal(CredentialPrincipalRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("workerPackApp"))
                .eventScopes(List.of(GeoLookupTool.EVENT_CODE))
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
