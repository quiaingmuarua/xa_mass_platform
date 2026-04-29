package com.xa.mass.api.internal;

import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.ExternalWorkerOperations;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
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
    private ExternalWorkerOperations externalWorkerOperations;

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
        lenient().when(externalWorkerOperations.getWorkerAdapterId("node-worker-1"))
                .thenReturn(WorkerTransportHints.POLLING);
        lenient().when(externalWorkerOperations.getWorkerTransportHint("node-worker-1"))
                .thenReturn(WorkerTransportHints.POLLING);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExternalWorkerApiController(externalWorkerOperations, authProvider))
                .setControllerAdvice(new com.xa.mass.api.aop.GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerWorkerDefaultsToPollingAndUsesEventBindings() throws Exception {
        mockMvc.perform(post("/worker-api/workers/register")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1",
                                  "workerGroupId": "node-runtime",
                                  "attributes": {
                                    "lang": "node"
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
                .andExpect(jsonPath("$.data.workerId").value("node-worker-1"))
                .andExpect(jsonPath("$.data.adapterId").value(WorkerTransportHints.POLLING))
                .andExpect(jsonPath("$.data.transportHint").value(WorkerTransportHints.POLLING))
                .andExpect(jsonPath("$.data.eventBindings[0].eventCode").value("crawler.fetch-page"));

        verify(externalWorkerOperations).registerWorker(argThat(request ->
                "node-worker-1".equals(request.getWorkerId())
                        && request.getAdapterId() == null
                        && WorkerTransportHints.POLLING.equals(request.getTransportHint())
                        && List.of(WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(List.of("crawlerApp"))
                        .build()).equals(request.getEventBindings())
        ));
    }

    @Test
    void registerWorkerRejectsMissingEventBindings() throws Exception {
        mockMvc.perform(post("/worker-api/workers/register")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("eventBindings is required"));
    }

    @Test
    void registerWorkerRejectsTransportHintCompatibilityAlias() throws Exception {
        mockMvc.perform(post("/worker-api/workers/register")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1",
                                  "transportHint": "pull",
                                  "eventBindings": [
                                    {
                                      "eventCode": "crawler.fetch-page",
                                      "projectCodes": ["crawlerApp"]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("External worker API supports only polling or realtime transport"));
    }

    @Test
    void workerApiRequiresCredential() throws Exception {
        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
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
        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "other-worker")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential worker binding denied: other-worker"));
    }

    @Test
    void workerApiRejectsEventScopeMismatchOnRegister() throws Exception {
        mockMvc.perform(post("/worker-api/workers/register")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "node-worker-key")
                        .content("""
                                {
                                  "workerId": "node-worker-1",
                                  "eventBindings": [
                                    {
                                      "eventCode": "mock.reset"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential event scope denied: mock.reset"));
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

        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
                        .contentType("application/json")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "task-only-key")
                        .content("""
                                {
                                  "maxMessages": 1
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential permission denied: worker:poll"));
    }

    @Test
    void pollTasksReturnsTransportNeutralItems() throws Exception {
        when(externalWorkerOperations.pollTasks("node-worker-1", 2, 250L)).thenReturn(List.of(
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

        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
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
    void pollTasksRejectsNegativeTimeout() throws Exception {
        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
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
        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
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
        when(externalWorkerOperations.submitResult(eq("node-worker-1"), argThat(report ->
                "task-1".equals(report.getTaskId())
                        && "msg-1".equals(report.getMessageId())
                        && report.isSuccess()
                        && "ok".equals(report.getDetail())
                        && Map.of("title", "Example").equals(report.getOutput())
        ))).thenReturn(true);

        mockMvc.perform(post("/worker-api/workers/{workerId}/results", "node-worker-1")
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

        verify(externalWorkerOperations).submitResult(eq("node-worker-1"), argThat(report ->
                "task-1".equals(report.getTaskId())
                        && "msg-1".equals(report.getMessageId())
                        && report.isSuccess()
                        && "ok".equals(report.getDetail())
                        && Map.of("title", "Example").equals(report.getOutput())
        ));
    }

    @Test
    void pollEndpointsRejectRealtimeWorkers() throws Exception {
        when(externalWorkerOperations.getWorkerTransportHint("node-worker-1"))
                .thenReturn(WorkerTransportHints.REALTIME);

        mockMvc.perform(post("/worker-api/workers/{workerId}/poll", "node-worker-1")
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
}
