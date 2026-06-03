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
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.workerpack.tool.probe.ProbeWorkerPack;
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
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PhoneDeviceWorkerPackExternalSdkIntegrationTest extends AbstractSampleE2eTest {
    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void phoneDeviceWorkerPackMatchesFingerprintAndCompletesThroughJavaSdk() throws Exception {
        String matchedWorkerId = "phone-device-probe-poll-sg-001";
        String otherWorkerId = "phone-device-probe-poll-sg-002";
        String matchedWorkerKey = "phone-device-probe-worker-a-key";
        String otherWorkerKey = "phone-device-probe-worker-b-key";
        String submitterKey = "phone-device-probe-submitter-key";
        registerDeviceProbeCatalogFixture();
        registerTaskSubmitter("phone-device-probe-submitter", submitterKey);
        registerWorkerSubmitter("phone-device-probe-worker-a", matchedWorkerKey, matchedWorkerId);
        registerWorkerSubmitter("phone-device-probe-worker-b", otherWorkerKey, otherWorkerId);
        sdkApp().replaceDefaultRules(List.of(
                rule("phone-worker-online", "hasWorkerSchedulingResource == true"),
                rule("phone-worker-capacity", "hasWorkerSchedulingResource == true"),
                rule("phone-worker-project", "supportsProject == true"),
                rule("phone-worker-fingerprint", "matchesTargetWorkerAttributes == true")
        ));

        MassPlatform matchedWorkerMass = platform(matchedWorkerKey);
        MassPlatform otherWorkerMass = platform(otherWorkerKey);
        MassPlatform submitterMass = platform(submitterKey);

        try (PollingWorkerSession matched = ProbeWorkerPack.phoneDevicePolling(matchedWorkerMass)
                .workerId(matchedWorkerId)
                .adapterNodeId("phone-device-probe-node-a")
                .attribute("fingerprintProfile", "fp-android-sg-a")
                .attribute("fingerprintHash", "sha256:dev-fp-android-sg-a-001")
                .attribute("networkOperatorMccMnc", "52501")
                .attribute("deviceModel", "Pixel-7a")
                .pollInterval(Duration.ofMillis(50))
                .heartbeatInterval(Duration.ofMillis(100))
                .startPolling();
             PollingWorkerSession other = ProbeWorkerPack.phoneDevicePolling(otherWorkerMass)
                     .workerId(otherWorkerId)
                     .adapterNodeId("phone-device-probe-node-b")
                     .attribute("fingerprintProfile", "fp-android-sg-b")
                     .attribute("fingerprintHash", "sha256:dev-fp-android-sg-b-002")
                     .attribute("networkOperatorMccMnc", "52505")
                     .attribute("deviceModel", "Galaxy-A54")
                     .pollInterval(Duration.ofMillis(50))
                     .heartbeatInterval(Duration.ofMillis(100))
                     .startPolling()) {
            String taskId = submitterMass.tasks().create(TaskCreateRequest.builder()
                    .project("deviceProbe")
                    .userId("worker-pack-agent")
                    .contract(TaskContract.BATCH)
                    .sharedConfig("requiredFingerprintProfile", "fp-android-sg-a")
                    .sharedConfig("requiredNetworkOperatorMccMnc", "52501")
                    .targetWorkerAttributes(Map.of("fingerprintProfile", "fp-android-sg-a"))
                    .executionSpec(TaskExecutionSpec.builder()
                            .batchSize(1)
                            .build())
                    .build()).taskId();
            submitterMass.tasks().appendItems(taskId, TaskItemBatch.builder()
                    .eventCode(ProbeWorkerPack.PHONE_METADATA_EVENT)
                    .item(Map.of(
                            "phoneNumber", "+6591234567",
                            "defaultRegion", "SG",
                            "requiredFingerprintProfile", "fp-android-sg-a",
                            "requiredNetworkOperatorMccMnc", "52501",
                            "expectedOutcome", "VALID_E164",
                            "traceLabel", "stage2-phone-fingerprint-proof"
                    ))
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
            assertEquals(matchedWorkerId, results.items().getFirst().workerId());
            assertEquals("SG", results.items().getFirst().output().get("region"));
            assertEquals("VALID_E164", results.items().getFirst().output().get("classification"));
        }
    }

    private void registerDeviceProbeCatalogFixture() {
        sdkApp().registerEventDefinition(EventDefinition.builder()
                .code(ProbeWorkerPack.PHONE_METADATA_EVENT)
                .name("Phone Metadata Probe")
                .description("Validate phone number metadata and carrier hints without public network access.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("deviceProbe"))
                .build());
        sdkApp().registerProject(ProjectDefinition.builder()
                .code("deviceProbe")
                .name("Device Probe")
                .description("Device and phone metadata probe fixtures.")
                .eventCodes(List.of(ProbeWorkerPack.PHONE_METADATA_EVENT))
                .build());
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
                .projectScopes(List.of("deviceProbe"))
                .eventScopes(List.of(ProbeWorkerPack.PHONE_METADATA_EVENT))
                .build());
    }

    private void registerWorkerSubmitter(String principalId, String credential, String workerId) {
        sdkApp().registerSubmitter(SubmitterRegistration.builder()
                .principalId(principalId)
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("deviceProbe"))
                .eventScopes(List.of(ProbeWorkerPack.PHONE_METADATA_EVENT))
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
