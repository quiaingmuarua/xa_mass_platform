package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.WorkerClientOperations;
import com.xa.mass.sdk.WorkerRegistryOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerStateProjectionSnapshot;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExternalWorkerApiControllerTest {

    @Mock
    private WorkerRegistryOperations workerRegistry;

    @Mock
    private WorkerClientOperations workerClient;

    @Mock
    private WorkerControlOperations workerControl;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;
    private PrincipalContext workerSubmitter;

    @BeforeEach
    void setUp() {
        workerSubmitter = new PrincipalContext(
                "node-worker-1",
                null,
                null,
                List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of("workerId", "node-worker-1")
        );
        lenient().when(authProvider.authenticate("node-worker-key")).thenReturn(workerSubmitter);
        lenient().when(workerClient.getWorkerAdapterId("node-worker-1"))
                .thenReturn(WorkerTransportHints.POLLING);
        lenient().when(workerClient.getWorkerTransportHint("node-worker-1"))
                .thenReturn(WorkerTransportHints.POLLING);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExternalWorkerApiController(
                        workerRegistry,
                        workerClient,
                        workerControl,
                        new ApiAuthorizationService(authProvider, null)))
                .setControllerAdvice(new com.xa.mass.api.aop.GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerAdapterNodeUsesExplicitEndpointIdentity() throws Exception {
        mockMvc.perform(post("/worker-api/v1/adapter-nodes")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "adapterNodeId": "node-a",
                                  "adapterType": "polling",
                                  "adapterVersion": "1.0.0",
                                  "endpointId": "runtime-a",
                                  "attributes": {
                                    "region": "us"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.adapterNodeId").value("node-a"))
                .andExpect(jsonPath("$.data.adapterType").value("polling"))
                .andExpect(jsonPath("$.data.endpointId").value("runtime-a"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.online").value(true));

        verify(workerRegistry).registerAdapterNode(argThat(request ->
                "node-a".equals(request.getAdapterNodeId())
                        && "polling".equals(request.getAdapterType())
                        && "1.0.0".equals(request.getAdapterVersion())
                        && "runtime-a".equals(request.getEndpointId())
                        && request.isEnabled()
                        && request.isOnline()
                        && Map.of("region", "us").equals(request.getAttributes())
        ));
    }

    @Test
    void declareWorkerGroupUsesEventBindingsAsGroupCapabilityTruth() throws Exception {
        mockMvc.perform(post("/worker-api/v1/worker-groups")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "groupId": "node-runtime",
                                  "defaultMaxConcurrentWork": 3,
                                  "defaultAttributes": {
                                    "runtime": "node"
                                  },
                                  "eventBindings": [
                                    {
                                      "eventCode": "crawler.fetch-page",
                                      "projectCodes": ["crawlerApp"]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groupId").value("node-runtime"))
                .andExpect(jsonPath("$.data.defaultMaxConcurrentWork").value(3))
                .andExpect(jsonPath("$.data.defaultAttributes.runtime").value("node"))
                .andExpect(jsonPath("$.data.eventBindings[0].eventCode").value("crawler.fetch-page"));

        verify(workerRegistry).declareWorkerGroup(argThat(request ->
                "node-runtime".equals(request.getGroupId())
                        && request.getDefaultMaxConcurrentWork() == 3
                        && Map.of("runtime", "node").equals(request.getDefaultAttributes())
                        && List.of(WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(List.of("crawlerApp"))
                        .build()).equals(request.getEventBindings())
        ));
    }

    @Test
    void declareWorkerGroupRejectsMissingEventBindings() throws Exception {
        mockMvc.perform(post("/worker-api/v1/worker-groups")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "groupId": "node-runtime"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("eventBindings is required"));
    }

    @Test
    void declareWorkerGroupRejectsEventScopeMismatch() throws Exception {
        mockMvc.perform(post("/worker-api/v1/worker-groups")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "groupId": "node-runtime",
                                  "eventBindings": [
                                    {
                                      "eventCode": "mock.reset"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Worker credential event scope denied: mock.reset"));
    }

    @Test
    void bindNodeGroupUsesAdapterNodeAndWorkerGroupOnly() throws Exception {
        mockMvc.perform(post("/worker-api/v1/node-group-bindings")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "adapterNodeId": "node-a",
                                  "workerGroupId": "node-runtime",
                                  "pluginVersion": "1.1.0",
                                  "deploymentVersion": "deploy-7",
                                  "attributes": {
                                    "route": "us"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.adapterNodeId").value("node-a"))
                .andExpect(jsonPath("$.data.workerGroupId").value("node-runtime"))
                .andExpect(jsonPath("$.data.pluginVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.deploymentVersion").value("deploy-7"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.draining").value(false));

        verify(workerRegistry).bindNodeGroup(argThat(request ->
                "node-a".equals(request.getAdapterNodeId())
                        && "node-runtime".equals(request.getWorkerGroupId())
                        && "1.1.0".equals(request.getPluginVersion())
                        && "deploy-7".equals(request.getDeploymentVersion())
                        && request.isEnabled()
                        && !request.isDraining()
                        && Map.of("route", "us").equals(request.getAttributes())
        ));
    }

    @Test
    void registerWorkerDefaultsToPollingAndUsesIdentityOnlyPayload() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1",
                                  "adapterNodeId": "node-a",
                                  "workerGroupId": "node-runtime",
                                  "attributes": {
                                    "lang": "node"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("node-worker-1"))
                .andExpect(jsonPath("$.data.adapterNodeId").value("node-a"))
                .andExpect(jsonPath("$.data.workerGroupId").value("node-runtime"))
                .andExpect(jsonPath("$.data.adapterId").value(WorkerTransportHints.POLLING))
                .andExpect(jsonPath("$.data.transportHint").value(WorkerTransportHints.POLLING))
                .andExpect(jsonPath("$.data.eventBindings").doesNotExist());

        verify(workerRegistry).registerWorker(argThat(request ->
                "node-worker-1".equals(request.getWorkerId())
                        && "node-a".equals(request.getAdapterNodeId())
                        && "node-runtime".equals(request.getWorkerGroupId())
                        && request.getAdapterId() == null
                        && WorkerTransportHints.POLLING.equals(request.getTransportHint())
        ));
    }

    @Test
    void registerWorkerAcceptsGroupFirstRegistrationWithoutEventBindings() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1",
                                  "adapterNodeId": "node-a",
                                  "workerGroupId": "node-runtime"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("node-worker-1"))
                .andExpect(jsonPath("$.data.workerGroupId").value("node-runtime"))
                .andExpect(jsonPath("$.data.eventBindings").doesNotExist());

        verify(workerRegistry).registerWorker(argThat(request ->
                "node-worker-1".equals(request.getWorkerId())
                        && "node-a".equals(request.getAdapterNodeId())
                        && "node-runtime".equals(request.getWorkerGroupId())
        ));
    }

    @Test
    void registerWorkerRejectsMissingWorkerGroupId() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("workerGroupId must not be blank"));
    }

    @Test
    void registerWorkerRejectsTransportHintCompatibilityAlias() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                {
                  "workerId": "node-worker-1",
                  "adapterNodeId": "node-a",
                  "workerGroupId": "node-runtime",
                  "transportHint": "pull"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("External worker API supports only polling or realtime transport"));
    }

    @Test
    void workerApiRequiresCredential() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("Invalid or missing worker credential"));
    }

    @Test
    void workerApiRejectsWorkerIdBindingMismatch() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "other-worker")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Worker credential binding denied: other-worker"));
    }

    @Test
    void registerWorkerRejectsEventBindingsField() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                {
                  "workerId": "node-worker-1",
                  "adapterNodeId": "node-a",
                  "workerGroupId": "node-runtime",
                  "eventBindings": [
                                    {
                                      "eventCode": "mock.reset"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("Unsupported worker register fields: eventBindings"));
    }

    @Test
    void workerApiRejectsMissingWorkerPermission() throws Exception {
        when(authProvider.authenticate("task-only-key")).thenReturn(new PrincipalContext(
                "node-worker-1",
                null,
                null,
                List.of(PrincipalContext.TASK_CREATE_PERMISSION),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of("workerId", "node-worker-1")
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "task-only-key")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Worker credential permission denied: worker:poll"));
    }

    @Test
    void pollTasksReturnsTransportNeutralItems() throws Exception {
        when(workerClient.pollTasks("node-worker-1", 2, 250L)).thenReturn(List.of(
                new TaskDispatchItem(
                        "task-1",
                        "msg-1",
                        "crawler.fetch-page",
                        "fetch-page",
                        "crawlerApp",
                        "user-1",
                        0,
                        "node-worker-1",
                        "ctx-node-1",
                        null,
                        Map.of("url", "https://example.test"),
                        Map.of("timeoutMs", 1000)
                )
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 2,
                                  "timeoutMs": 250
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].eventCode").value("crawler.fetch-page"))
                .andExpect(jsonPath("$.data.items[0].input.url").value("https://example.test"));
    }

    @Test
    void pollWorkerCommandsReturnsWorkerOwnedCommands() throws Exception {
        WorkerCommandSnapshot command = new WorkerCommandSnapshot(
                "cmd-poll-001",
                "node-worker-1",
                "PING",
                "DELIVERY_ACCEPTED",
                "operator",
                "probe",
                "idem-1",
                1770000000000L,
                Map.of("mode", "check"),
                "command pulled by worker",
                1,
                Instant.parse("2026-05-20T10:00:00Z"),
                Instant.parse("2026-05-20T10:00:00Z"),
                Instant.parse("2026-05-20T10:00:01Z")
        );
        when(workerControl.pullWorkerCommands("node-worker-1", 2)).thenReturn(List.of(command));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}/commands:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxCommands": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.commands[0].commandId").value("cmd-poll-001"))
                .andExpect(jsonPath("$.data.commands[0].status").value("DELIVERY_ACCEPTED"));

        verify(workerControl).pullWorkerCommands("node-worker-1", 2);
    }

    @Test
    void pollWorkerCommandsRejectsInvalidLimit() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}/commands:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxCommands": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("maxCommands must be greater than 0"));
    }

    @Test
    void pollTasksRejectsNegativeTimeout() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 1,
                                  "timeoutMs": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("timeoutMs must be greater than or equal to 0"));
    }

    @Test
    void pollTasksRejectsTimeoutAboveLimit() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 1,
                                  "timeoutMs": 30001
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("timeoutMs must be less than or equal to 30000"));
    }

    @Test
    void submitResultMapsRequestToTransportReport() throws Exception {
        when(workerClient.submitResult(eq("node-worker-1"), argThat(report ->
                "task-1".equals(report.getTaskId())
                        && "msg-1".equals(report.getMessageId())
                        && report.isSuccess()
                        && "ok".equals(report.getDetail())
                        && Map.of("title", "Example").equals(report.getOutput())
        ))).thenReturn(true);

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:submit-result", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "messageId": "msg-1",
                                  "success": true,
                                  "detail": "ok",
                                  "output": {
                                    "title": "Example"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("node-worker-1"))
                .andExpect(jsonPath("$.data.submitted").value(true));

        verify(workerClient).submitResult(eq("node-worker-1"), argThat(report ->
                "task-1".equals(report.getTaskId())
                        && "msg-1".equals(report.getMessageId())
                        && report.isSuccess()
                        && "ok".equals(report.getDetail())
                        && Map.of("title", "Example").equals(report.getOutput())
        ));
    }

    @Test
    void pollEndpointsRejectRealtimeWorkers() throws Exception {
        when(workerClient.getWorkerTransportHint("node-worker-1"))
                .thenReturn(WorkerTransportHints.REALTIME);

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value(
                        "External worker API poll only supports polling workers; worker node-worker-1 uses transport 'realtime'"));
    }

    @Test
    void reportCapabilityDefaultsVersionAndDelegatesToWorkerControl() throws Exception {
        when(workerControl.reportWorkerCapability(any())).thenReturn(new WorkerCapabilityReportSnapshot(
                "ACCEPTED", "node-worker-1", 1L, true, true, "updated"
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:report-capability", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "availableEventCodes":["crawler.fetch-page"],
                                  "schedulingAttributes":{"country":"us"},
                                  "agentVersion":"1.2.3"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("node-worker-1"))
                .andExpect(jsonPath("$.data.accepted").value(true));

        ArgumentCaptor<WorkerCapabilityReportRequest> captor =
                ArgumentCaptor.forClass(WorkerCapabilityReportRequest.class);
        verify(workerControl).reportWorkerCapability(captor.capture());
        WorkerCapabilityReportRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("node-worker-1", request.workerId());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("crawler.fetch-page"), request.availableEventCodes());
        org.junit.jupiter.api.Assertions.assertTrue(request.capabilityVersion() > 0L);
    }

    @Test
    void reportCapabilityRejectsNonPositiveVersion() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:report-capability", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "capabilityVersion":0,
                                  "availableEventCodes":["crawler.fetch-page"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("capabilityVersion must be greater than 0"));
    }

    @Test
    void reportCapabilityRejectsEventScopeMismatch() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:report-capability", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "availableEventCodes":["mock.reset"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Worker credential event scope denied: mock.reset"));
    }

    @Test
    void reportStateDefaultsVersionAndConstrainsStateEnum() throws Exception {
        when(workerControl.reportWorkerState(any())).thenReturn(new WorkerStateReportSnapshot(
                "ACCEPTED",
                "node-worker-1",
                1L,
                true,
                true,
                "updated",
                new WorkerStateProjectionSnapshot(
                        "node-worker-1",
                        1L,
                        "DRAINING",
                        "maintenance",
                        Instant.parse("2026-05-20T10:00:00Z"),
                        Instant.parse("2026-05-20T10:00:01Z")
                )
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:report-state", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "state":"DRAINING",
                                  "reason":"maintenance",
                                  "attributes":{"source":"worker"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projection.state").value("DRAINING"));

        ArgumentCaptor<WorkerStateReportRequest> captor =
                ArgumentCaptor.forClass(WorkerStateReportRequest.class);
        verify(workerControl).reportWorkerState(captor.capture());
        WorkerStateReportRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("node-worker-1", request.workerId());
        org.junit.jupiter.api.Assertions.assertEquals("DRAINING", request.state());
        org.junit.jupiter.api.Assertions.assertTrue(request.stateVersion() > 0L);
    }

    @Test
    void reportStateRejectsUnknownState() throws Exception {
        mockMvc.perform(post("/worker-api/v1/workers/{workerId}:report-state", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "state":"BUSY"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("state must be one of AVAILABLE, DEGRADED, DRAINING, OFFLINE"));
    }

    @Test
    void acknowledgeCommandRequiresBoundCommandOwnership() throws Exception {
        WorkerCommandSnapshot command = new WorkerCommandSnapshot(
                "cmd-001",
                "node-worker-1",
                "DRAIN",
                "REQUESTED",
                "operator",
                "maintenance",
                "idem-1",
                1770000000000L,
                Map.of("mode", "soft"),
                null,
                0,
                null,
                Instant.parse("2026-05-20T10:00:00Z"),
                Instant.parse("2026-05-20T10:00:00Z")
        );
        when(workerControl.getWorkerCommand("cmd-001")).thenReturn(command);
        when(workerControl.acknowledgeWorkerCommand(any())).thenReturn(new WorkerCommandResultSnapshot(
                "ACCEPTED", true, "REQUESTED", "DELIVERY_ACCEPTED", "accepted", command
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}/commands/{commandId}:ack", "node-worker-1", "cmd-001")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "status":"DELIVERY_ACCEPTED",
                                  "reason":"accepted"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStatus").value("DELIVERY_ACCEPTED"));

        verify(workerControl).acknowledgeWorkerCommand(eq(new WorkerCommandAcknowledgementRequest(
                "cmd-001", "DELIVERY_ACCEPTED", "accepted"
        )));
    }

    @Test
    void acknowledgeCommandRejectsOtherWorkersCommand() throws Exception {
        when(workerControl.getWorkerCommand("cmd-001")).thenReturn(new WorkerCommandSnapshot(
                "cmd-001",
                "other-worker",
                "DRAIN",
                "REQUESTED",
                "operator",
                "maintenance",
                "idem-1",
                1770000000000L,
                Map.of(),
                null,
                0,
                null,
                Instant.parse("2026-05-20T10:00:00Z"),
                Instant.parse("2026-05-20T10:00:00Z")
        ));

        mockMvc.perform(post("/worker-api/v1/workers/{workerId}/commands/{commandId}:ack", "node-worker-1", "cmd-001")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "status":"DELIVERY_ACCEPTED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("worker command does not belong to worker node-worker-1"));
    }
}
