package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.usage.ApiUsageLedgerRecord;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.api.auth.usage.ApiUsageStatus;
import com.xa.mass.api.auth.usage.InMemoryApiUsageLedgerStore;
import com.xa.mass.api.review.InMemoryTaskReviewStore;
import com.xa.mass.api.review.TaskReviewAttemptClosedEvent;
import com.xa.mass.api.review.TaskReviewItemsAcceptedEvent;
import com.xa.mass.api.review.TaskReviewReadModelWriter;
import com.xa.mass.api.review.TaskReviewStoreMaterializer;
import com.xa.mass.api.review.TaskReviewStoreTaskReviewReadModel;
import com.xa.mass.api.review.TaskReviewWorkTerminalEvent;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.api.sync.TaskSyncRequestSupervisor;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.TaskResultQueryOperations;
import com.xa.mass.sdk.TaskStageEvidenceOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.MassTaskCommandRequest;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskAccessSnapshot;
import com.xa.mass.sdk.model.TaskCommandResult;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskResultArchiveSnapshot;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskStageEvidenceRequest;
import com.xa.mass.sdk.model.TaskStageEvidenceSnapshot;
import com.xa.mass.sdk.model.TaskStageProjectionSnapshot;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskApiControllerTest {

    private static final String TASK_ID = "task-001";

    @Mock
    private TaskQueryOperations taskQueries;

    @Mock
    private TaskResultQueryOperations taskResultQueries;

    @Mock
    private TaskAdminOperations taskAdmin;

    @Mock
    private AuthProvider authProvider;

    @Mock
    private SyncTaskResultBridge syncTaskResultBridge;

    @Mock
    private TaskStageEvidenceOperations taskStageEvidence;

    private TaskSyncRequestSupervisor taskSyncRequestSupervisor;
    private InMemoryApiUsageLedgerStore usageStore;
    private InMemoryTaskReviewStore taskReviewStore;
    private TaskApiController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        taskSyncRequestSupervisor = new TaskSyncRequestSupervisor(null, 500, 100, 20);
        usageStore = new InMemoryApiUsageLedgerStore();
        taskReviewStore = new InMemoryTaskReviewStore();
        controller = new TaskApiController(taskQueries, taskResultQueries, taskAdmin, createTaskCatalog(),
                ApiAuthTestSupport.defaultOperatorAuthService(), new ApiAuthorizationService(authProvider, null),
                new TaskSecurityViewSupport(), syncTaskResultBridge, taskSyncRequestSupervisor, taskStageEvidence);
        controller.setApiUsageLedgerService(new ApiUsageLedgerService(usageStore));
        controller.setTaskReviewReadModelWriter(directReviewWriter());
        mockMvc = MockMvcBuilders.standaloneSetup(
                controller,
                new InternalTaskReviewController(taskQueries, reviewReadModel())
        ).build();
    }

    @Test
    void createTaskShellDelegatesToSdkShellCreate() throws Exception {
        TaskShellSnapshot createdTask = taskShell("task-001", "demoApp", "agent");
        when(taskAdmin.createTaskShell(any(MassTaskShellCreateRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"demoApp",
                                  "sharedConfig":{"textContent":"hello"},
                                  "userId":"agent",
                                  "executionSpec":{"workloadClass":"INTERACTIVE","batchSize":2,"foreground":false}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.project").value("demoApp"))
                .andExpect(jsonPath("$.data.task.execution.workloadClass").value("INTERACTIVE"))
                .andExpect(jsonPath("$.data.task.execution.batchSize").value(2))
                .andExpect(jsonPath("$.data.task.execution.foreground").value(false))
                .andExpect(jsonPath("$.data.task.fieldSources.taskId").value("controlPlaneShell"))
                .andExpect(jsonPath("$.data.task.fieldSources.executionSpec").value("compatibilityAlias"))
                .andExpect(jsonPath("$.data.task.counters").doesNotExist())
                .andExpect(jsonPath("$.data.task.timestamps").doesNotExist())
                .andExpect(jsonPath("$.data.task.status").doesNotExist());

        ArgumentCaptor<MassTaskShellCreateRequest> captor = ArgumentCaptor.forClass(MassTaskShellCreateRequest.class);
        verify(taskAdmin).createTaskShell(captor.capture());
        MassTaskShellCreateRequest request = captor.getValue();
        assertEquals("demoApp", request.getProject());
        assertEquals("default", request.getTenantId());
        assertEquals("agent", request.getUserId());
        assertNull(request.getContract());
        assertEquals(2, request.getExecutionSpec().getBatchSize());
        assertEquals("INTERACTIVE", request.getExecutionSpec().getWorkloadClass());
        assertEquals(false, request.getExecutionSpec().isForeground());
    }

    @Test
    void createTaskShellRejectsLegacyNestedExecutionSpecContract() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"demoApp",
                                  "userId":"agent",
                                  "executionSpec":{"contract":"SESSION","workloadClass":"INTERACTIVE"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(taskAdmin, never()).createTaskShell(any());
    }

    @Test
    void createTaskShellRejectsLegacyInputsField() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"demoApp",
                                  "userId":"agent",
                                  "inputs":[{"target":"alpha"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(taskAdmin, never()).createTaskShell(any());
    }

    @Test
    void createTaskShellWithSdkCredentialUsesApiKeyScope() throws Exception {
        TaskShellSnapshot createdTask = taskShell("task-sdk-001", "crawlerApp", "crawler-agent");

        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-agent",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of("transport", "polling")
        ));
        when(taskAdmin.createTaskShell(any(MassTaskShellCreateRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sharedConfig":{"eventCode":"crawler.fetch-page"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-sdk-001"))
                .andExpect(jsonPath("$.data.project").value("crawlerApp"))
                .andExpect(jsonPath("$.data.userId").value("crawler-agent"));
    }

    @Test
    void apiKeyTaskCreateAndResultReadRecordAcceptedUsage() throws Exception {
        PrincipalContext apiKeyPrincipal = new PrincipalContext(
                "agent",
                null,
                "crawlerApp",
                List.of("task:create", "task:view"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-usage-1")
        );
        when(authProvider.authenticate("usage-key")).thenReturn(apiKeyPrincipal);
        when(taskAdmin.createTaskShell(any(MassTaskShellCreateRequest.class)))
                .thenReturn(taskShell(TASK_ID, "crawlerApp", "agent"));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Mass-Api-Key", "usage-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sharedConfig":{"eventCode":"crawler.fetch-page"}
                                }
                                """))
                .andExpect(status().isOk());

        List<ApiUsageLedgerRecord> afterCreate = usageStore.listByKeyId("ak-usage-1");
        assertEquals(1, afterCreate.size());
        assertEquals(ApiUsageOperation.TASK_CREATE, afterCreate.get(0).operation());
        assertEquals(1, afterCreate.get(0).units());
        assertEquals(TASK_ID, afterCreate.get(0).taskId());

        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "crawlerApp"));
        when(taskResultQueries.readTaskResults(TASK_ID, 0, 200)).thenReturn(new TaskResultWindowSnapshot(
                TASK_ID,
                List.of(
                        resultRow(1, "msg-001", "SUCCESS", "worker-001"),
                        resultRow(2, "msg-002", "SUCCESS", "worker-002")
                ),
                2,
                false,
                2
        ));
        when(taskResultQueries.getTaskResultArchiveManifest(TASK_ID)).thenReturn(new TaskResultArchiveSnapshot(
                TASK_ID, false, "ndjson", "application/x-ndjson", "gzip", 0, null, null));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/results", TASK_ID)
                        .header("X-Mass-Api-Key", "usage-key"))
                .andExpect(status().isOk());

        List<ApiUsageLedgerRecord> afterRead = usageStore.listByKeyId("ak-usage-1");
        assertEquals(2, afterRead.size());
        assertEquals(ApiUsageOperation.TASK_RESULT_READ, afterRead.get(1).operation());
        assertEquals(2, afterRead.get(1).units());
        assertEquals(TASK_ID, afterRead.get(1).taskId());
    }

    @Test
    void apiKeyTaskCreateScopeDenialRecordsRejectedUsage() throws Exception {
        PrincipalContext apiKeyPrincipal = new PrincipalContext(
                "agent",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-reject-1")
        );
        when(authProvider.authenticate("denied-key")).thenReturn(apiKeyPrincipal);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("X-Mass-Api-Key", "denied-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"demoApp",
                                  "userId":"agent"
                                }
                                """))
                .andExpect(status().isForbidden());

        List<ApiUsageLedgerRecord> records = usageStore.listByKeyId("ak-reject-1");
        assertEquals(1, records.size());
        assertEquals(ApiUsageOperation.TASK_CREATE, records.get(0).operation());
        assertEquals(ApiUsageStatus.REJECTED, records.get(0).status());
        assertEquals("demoApp", records.get(0).project());
        assertEquals(0, records.get(0).units());
        verify(taskAdmin, never()).createTaskShell(any(MassTaskShellCreateRequest.class));
    }

    @Test
    void apiKeyTaskResultOwnerMismatchRecordsRejectedUsage() throws Exception {
        PrincipalContext apiKeyPrincipal = new PrincipalContext(
                "other-agent",
                null,
                "crawlerApp",
                List.of("task:view"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-reject-2")
        );
        when(authProvider.authenticate("viewer-key")).thenReturn(apiKeyPrincipal);
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "crawlerApp"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/results", TASK_ID)
                        .header("X-Mass-Api-Key", "viewer-key"))
                .andExpect(status().isForbidden());

        List<ApiUsageLedgerRecord> records = usageStore.listByKeyId("ak-reject-2");
        assertEquals(1, records.size());
        assertEquals(ApiUsageOperation.TASK_RESULT_READ, records.get(0).operation());
        assertEquals(ApiUsageStatus.REJECTED, records.get(0).status());
        assertEquals(TASK_ID, records.get(0).taskId());
        assertEquals(0, records.get(0).units());
        verify(taskResultQueries, never()).readTaskResults(any(), anyLong(), anyInt());
    }

    @Test
    void approveUsesCommandRoute() throws Exception {
        when(taskAdmin.executeTaskCommand(eq(TASK_ID), any(MassTaskCommandRequest.class)))
                .thenReturn(new TaskCommandResult(
                        TASK_ID, "APPROVE", true, true, "READY", "OPEN",
                        null, null, null, null
                ));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/commands", TASK_ID)
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, operatorPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "command":"APPROVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.command").value("APPROVE"))
                .andExpect(jsonPath("$.data.status").value("READY"));
    }

    @Test
    void taskStageEvidenceEndpointsDelegateToSdkSurface() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        TaskStageProjectionSnapshot projection = new TaskStageProjectionSnapshot(
                TASK_ID,
                "msg-001",
                "FETCH",
                2,
                "DONE",
                "page fetched",
                Instant.parse("2026-05-18T10:10:00Z"),
                Instant.parse("2026-05-18T10:10:01Z")
        );
        when(taskStageEvidence.reportTaskStageEvidence(any())).thenReturn(new TaskStageEvidenceSnapshot(
                "ACCEPTED", TASK_ID, "msg-001", "FETCH", 2, true, true, "updated", projection
        ));
        when(taskStageEvidence.getTaskStageProjection(TASK_ID, "msg-001", "FETCH")).thenReturn(projection);
        when(taskStageEvidence.listTaskStageProjections(TASK_ID, "msg-001")).thenReturn(List.of(projection));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}/evidence",
                        TASK_ID, "msg-001", "FETCH")
                        .contentType("application/json")
                        .content("""
                                {
                                  "stageVersion":2,
                                  "stageStatus":"DONE",
                                  "detail":"page fetched",
                                  "observedAt":"2026-05-18T10:10:00Z",
                                  "attributes":{"url":"https://example.test"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.projection.stageStatus").value("DONE"));

        ArgumentCaptor<TaskStageEvidenceRequest> captor =
                ArgumentCaptor.forClass(TaskStageEvidenceRequest.class);
        verify(taskStageEvidence).reportTaskStageEvidence(captor.capture());
        assertEquals(TASK_ID, captor.getValue().taskId());
        assertEquals("msg-001", captor.getValue().messageId());
        assertEquals("FETCH", captor.getValue().stageName());
        assertEquals("https://example.test", captor.getValue().attributes().get("url"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}",
                        TASK_ID, "msg-001", "FETCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stageStatus").value("DONE"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/items/{messageId}/stages", TASK_ID, "msg-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].stageName").value("FETCH"));
    }

    @Test
    void resumeReturnsTerminalCloseMessageWhenAlreadyCompletedWhilePaused() throws Exception {
        when(taskAdmin.executeTaskCommand(eq(TASK_ID), any(MassTaskCommandRequest.class)))
                .thenReturn(new TaskCommandResult(
                        TASK_ID, "RESUME", true, true, "TERMINAL", "SEALED",
                        "ALL_MESSAGES_SUCCEEDED", null, null, null
                ));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/commands", TASK_ID)
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, operatorPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "command":"RESUME"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINAL"))
                .andExpect(jsonPath("$.data.terminalReason").value("ALL_MESSAGES_SUCCEEDED"));
    }

    @Test
    void getTaskDoesNotReturnItemsByDefault() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("READY", "detail-task", "demoApp"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.tid").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.taskName").value("detail-task"))
                .andExpect(jsonPath("$.data.task.execution.batchSize").value(1))
                .andExpect(jsonPath("$.data.task.counters.targetCount").value(0))
                .andExpect(jsonPath("$.data.task.timestamps.updatedAt").value(""))
                .andExpect(jsonPath("$.data.task.sharedConfig.routingCode").value("us"))
                .andExpect(jsonPath("$.data.task.sharedConfig._massSecurity").doesNotExist())
                .andExpect(jsonPath("$.data.task.fieldSources.taskId").value("controlPlaneShell"))
                .andExpect(jsonPath("$.data.task.fieldSources.status").value("runtimeCurrent"))
                .andExpect(jsonPath("$.data.task.fieldSources.sharedConfig").value("controlPlaneShell"))
                .andExpect(jsonPath("$.data.security.createdByPrincipalId").value("agent"))
                .andExpect(jsonPath("$.data.security.createdByPrincipalType").value("SERVICE"))
                .andExpect(jsonPath("$.data.stateValidation").doesNotExist())
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void getTaskReviewReturnsSeedAndResultPreview() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        taskReviewStore.upsertItem(TASK_ID, reviewItem(
                "msg-001",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                Map.of("eventCode", "crawler.fetch-page", "url", "https://example.test/a"),
                Map.of("html", "<ok>"),
                "worker-001"));

        mockMvc.perform(get("/internal/v1/review/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalItems").value(1))
                .andExpect(jsonPath("$.data.seedPreview[0].eventCode").value("crawler.fetch-page"))
                .andExpect(jsonPath("$.data.resultPreview[0].workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.exports.seedUrl").value("/internal/v1/review/tasks/task-001/seed-export"));
    }

    @Test
    void exportTaskSeedsReturnsAttachmentPayload() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        taskReviewStore.upsertItem(TASK_ID, reviewItem(
                "msg-001",
                "ASSIGNED",
                null,
                Map.of("eventCode", "crawler.fetch-page", "url", "https://example.test/a"),
                Map.of(),
                "worker-001"));

        mockMvc.perform(get("/internal/v1/review/tasks/{taskId}/seed-export", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.rows[0].messageId").value("msg-001"));
    }

    @Test
    void getTaskResultsReturnsLiveOrderedWindow() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        when(taskResultQueries.readTaskResults(TASK_ID, 1, 2)).thenReturn(new TaskResultWindowSnapshot(
                TASK_ID,
                List.of(
                        resultRow(2, "msg-002", "SUCCESS", "worker-002"),
                        resultRow(3, "msg-003", "FAILED", "worker-003")
                ),
                3,
                false,
                3
        ));
        when(taskResultQueries.getTaskResultArchiveManifest(TASK_ID)).thenReturn(new TaskResultArchiveSnapshot(
                TASK_ID, false, "ndjson", "application/x-ndjson", "gzip", 0, null, null));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/results", TASK_ID)
                        .param("afterSeq", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LIVE"))
                .andExpect(jsonPath("$.data.taskTerminal").value(false))
                .andExpect(jsonPath("$.data.archiveReady").value(false))
                .andExpect(jsonPath("$.data.items[0].seq").value(2))
                .andExpect(jsonPath("$.data.items[0].messageId").value("msg-002"))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-002"))
                .andExpect(jsonPath("$.data.items[1].seq").value(3))
                .andExpect(jsonPath("$.data.nextAfterSeq").value(3))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void getTaskResultArchiveManifestReturnsArchiveMetadataForTerminalTask() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("TERMINAL", "detail-task", "demoApp"));
        when(taskResultQueries.getTaskResultArchiveManifest(TASK_ID)).thenReturn(new TaskResultArchiveSnapshot(
                TASK_ID, true, "ndjson", "application/x-ndjson", "gzip", 1, 64L, "checksum"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/results/archive", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.format").value("ndjson"))
                .andExpect(jsonPath("$.data.contentType").value("application/x-ndjson"))
                .andExpect(jsonPath("$.data.contentEncoding").value("gzip"))
                .andExpect(jsonPath("$.data.itemCount").value(1))
                .andExpect(jsonPath("$.data.downloadUrl").value("/api/v1/tasks/task-001/results/archive/content"));
    }

    @Test
    void appendTaskItemsPassesBatchEventCode() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 2, List.of("msg-001", "msg-002")));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "items":[{"text":"hello"},{"text":"world"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(2));

        ArgumentCaptor<MassTaskItemBatchAppendRequest> captor =
                ArgumentCaptor.forClass(MassTaskItemBatchAppendRequest.class);
        verify(taskAdmin).appendTaskItemsWithReceipt(org.mockito.ArgumentMatchers.eq(TASK_ID), captor.capture());
        assertEquals(List.of(Map.of("text", "hello"), Map.of("text", "world")), captor.getValue().getItems());
        assertEquals("chatbot.reply", captor.getValue().getEventCode());
        List<com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem> reviewItems =
                taskReviewStore.listItems(TASK_ID, 10);
        assertEquals(2, reviewItems.size());
        assertEquals("chatbot.reply", reviewItems.getFirst().input().get("eventCode"));
    }

    @Test
    void appendTaskItemsReturnsAcceptedAppendWhenReviewWriterFails() throws Exception {
        TaskReviewReadModelWriter writer = org.mockito.Mockito.mock(TaskReviewReadModelWriter.class);
        doThrow(new IllegalStateException("review write failed"))
                .when(writer).recordItemsAccepted(any(), any(), any(), any(), anyInt());
        controller.setTaskReviewReadModelWriter(writer);
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-001")));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "items":[{"text":"hello"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(1));

        verify(taskAdmin).appendTaskItemsWithReceipt(eq(TASK_ID), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemsRejectsMissingEventCode() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "items":[{"target":"hello"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("append requires batch eventCode or per-item eventCode"));

        verify(taskAdmin, never()).appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemsRejectsRemovedRetrySeedField() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "items":[{"text":"hello"}],
                                  "defaultMsgMaxRetryCount":5
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(taskAdmin, never()).appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemSyncReturnsStableFinalResult() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("READY", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-001")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-001")).thenReturn(future);
        when(syncTaskResultBridge.getExistingFinal(TASK_ID, "msg-001")).thenReturn(Optional.empty());

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"},
                                  "timeoutMs":2000
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        future.complete(new TaskWorkFinalSnapshot(
                TASK_ID,
                "msg-001",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                0,
                null,
                null,
                null,
                Map.of("ok", true)
        ));
        mvcResult.getAsyncResult(2000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.messageId").value("msg-001"))
                .andExpect(jsonPath("$.data.synced").value(true))
                .andExpect(jsonPath("$.data.timedOut").value(false))
                .andExpect(jsonPath("$.data.timeoutMs").value(2000))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.finalReason").value("BUSINESS_SUCCESS"))
                .andExpect(jsonPath("$.data.output.ok").value(true));
    }

    @Test
    void appendTaskItemSyncUsesItemEventCodeForAppendAndReview() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("READY", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-item-event")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-item-event")).thenReturn(future);
        when(syncTaskResultBridge.getExistingFinal(TASK_ID, "msg-item-event")).thenReturn(Optional.empty());

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "item":{"eventCode":"chatbot.reply","text":"hello"},
                                  "timeoutMs":2000
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        future.complete(new TaskWorkFinalSnapshot(
                TASK_ID,
                "msg-item-event",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                0,
                null,
                null,
                null,
                Map.of("ok", true)
        ));
        mvcResult.getAsyncResult(2000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value("msg-item-event"));

        ArgumentCaptor<MassTaskItemBatchAppendRequest> appendCaptor =
                ArgumentCaptor.forClass(MassTaskItemBatchAppendRequest.class);
        verify(taskAdmin).appendTaskItemsWithReceipt(eq(TASK_ID), appendCaptor.capture());
        assertEquals("chatbot.reply", appendCaptor.getValue().getEventCode());
        assertEquals("chatbot.reply",
                taskReviewStore.findItem(TASK_ID, "msg-item-event").orElseThrow().eventCode());
    }

    @Test
    void appendTaskItemSyncReturnsTimeoutWithoutCancellingAppend() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("RUNNING", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-009")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-009")).thenReturn(future);
        when(syncTaskResultBridge.getExistingFinal(TASK_ID, "msg-009")).thenReturn(Optional.empty());

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"},
                                  "timeoutMs":1
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult(2000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value("msg-009"))
                .andExpect(jsonPath("$.data.synced").value(false))
                .andExpect(jsonPath("$.data.timedOut").value(true))
                .andExpect(jsonPath("$.data.timeoutMs").value(1));
    }

    @Test
    void apiKeySyncAppendFailureAfterAcceptedRecordsFailedAfterAcceptUsage() throws Exception {
        PrincipalContext apiKeyPrincipal = new PrincipalContext(
                "agent",
                null,
                "demoApp",
                List.of("task:create"),
                List.of("demoApp"),
                List.of("chatbot.reply"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-sync-fail-1")
        );
        when(authProvider.authenticate("sync-fail-key")).thenReturn(apiKeyPrincipal);
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccessOwned("demoApp", "agent"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("RUNNING", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-sync-fail")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-sync-fail")).thenReturn(future);
        when(syncTaskResultBridge.getExistingFinal(TASK_ID, "msg-sync-fail")).thenReturn(Optional.empty());

        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .header("X-Mass-Api-Key", "sync-fail-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "clientRequestId":"request-sync-fail",
                                  "item":{"text":"hello"},
                                  "timeoutMs":2000
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        future.completeExceptionally(new IllegalStateException("bridge failed"));
        mvcResult.getAsyncResult(2000);

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isBadRequest());

        List<ApiUsageLedgerRecord> records = usageStore.listByKeyId("ak-sync-fail-1");
        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(record ->
                record.status() == ApiUsageStatus.ACCEPTED
                        && record.operation() == ApiUsageOperation.TASK_ITEM_SYNC_APPEND
                        && record.units() == 1));
        ApiUsageLedgerRecord failed = records.stream()
                .filter(record -> record.status() == ApiUsageStatus.FAILED_AFTER_ACCEPT)
                .findFirst()
                .orElseThrow();
        assertEquals(ApiUsageOperation.TASK_ITEM_SYNC_APPEND, failed.operation());
        assertEquals("msg-sync-fail", failed.messageId());
        assertEquals("request-sync-fail", failed.requestId());
        assertEquals(0, failed.units());
        assertEquals(400, failed.failureStatus());
        assertTrue(failed.failureReason().contains("bridge failed"));
    }

    @Test
    void apiKeyArchiveStreamingFailureAfterAcceptedRecordsFailedAfterAcceptUsage() throws Exception {
        PrincipalContext apiKeyPrincipal = new PrincipalContext(
                "agent",
                null,
                "demoApp",
                List.of("task:view"),
                List.of("demoApp"),
                List.of("*"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-archive-fail-1")
        );
        when(authProvider.authenticate("archive-fail-key")).thenReturn(apiKeyPrincipal);
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("TERMINAL", "detail-task", "demoApp"));
        when(taskResultQueries.getTaskResultArchiveManifest(TASK_ID)).thenReturn(new TaskResultArchiveSnapshot(
                TASK_ID, true, "ndjson", "application/x-ndjson", "gzip", 10, null, null));
        doThrow(new IllegalStateException("archive writer failed"))
                .when(taskResultQueries).writeTaskResultArchiveContent(eq(TASK_ID), any());

        org.springframework.http.ResponseEntity<?> response =
                controller.downloadTaskResultsArchive("archive-fail-key", null, TASK_ID);
        assertEquals(200, response.getStatusCode().value());
        StreamingResponseBody body = (StreamingResponseBody) response.getBody();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> body.writeTo(new ByteArrayOutputStream()));
        assertEquals("archive writer failed", failure.getMessage());

        List<ApiUsageLedgerRecord> records = usageStore.listByKeyId("ak-archive-fail-1");
        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(record ->
                record.status() == ApiUsageStatus.ACCEPTED
                        && record.operation() == ApiUsageOperation.TASK_ARCHIVE_DOWNLOAD
                        && record.units() == 1));
        ApiUsageLedgerRecord failed = records.stream()
                .filter(record -> record.status() == ApiUsageStatus.FAILED_AFTER_ACCEPT)
                .findFirst()
                .orElseThrow();
        assertEquals(ApiUsageOperation.TASK_ARCHIVE_DOWNLOAD, failed.operation());
        assertEquals(TASK_ID, failed.taskId());
        assertEquals(0, failed.units());
        assertEquals(500, failed.failureStatus());
        assertTrue(failed.failureReason().contains("archive writer failed"));
    }

    @Test
    void appendTaskItemSyncRejectsNonActiveTaskState() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("NEW", "OPEN"));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"}
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.msg").value("Task sync append requires READY or RUNNING task status"));

        verify(taskAdmin, never()).appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemSyncRejectsMultipleResolvedEventCodes() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("READY", "OPEN"));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"eventCode":"crawler.fetch-page","text":"hello"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("sync append requires exactly one resolved eventCode"));

        verify(taskAdmin, never()).appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemSyncRejectsWhenTaskSyncCapacityIsExceeded() throws Exception {
        TaskSyncRequestSupervisor zeroCapacitySupervisor = new TaskSyncRequestSupervisor(null, 500, 100, 1);
        zeroCapacitySupervisor.acquire("demoApp", TASK_ID);
        MockMvc capacityMvc = MockMvcBuilders.standaloneSetup(
                new TaskApiController(taskQueries, taskResultQueries, taskAdmin, createTaskCatalog(),
                        ApiAuthTestSupport.defaultOperatorAuthService(), new ApiAuthorizationService(authProvider, null),
                        new TaskSecurityViewSupport(), syncTaskResultBridge, zeroCapacitySupervisor, null),
                new InternalTaskReviewController(taskQueries, reviewReadModel())
        ).build();

        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("READY", "OPEN"));

        capacityMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"}
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.msg").value("Too many in-flight sync task requests for task: task-001"));
    }

    @Test
    void sealTaskUsesCommandRoute() throws Exception {
        when(taskAdmin.executeTaskCommand(eq(TASK_ID), any(MassTaskCommandRequest.class)))
                .thenReturn(new TaskCommandResult(
                        TASK_ID, "SEAL", true, true, "RUNNING", "SEALED",
                        null, null, null, null
                ));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/commands", TASK_ID)
                        .requestAttr(ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR, operatorPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "command":"SEAL"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.command").value("SEAL"))
                .andExpect(jsonPath("$.data.intakeStatus").value("SEALED"));
    }

    @Test
    void deleteTaskRouteIsNotMapped() throws Exception {
        mockMvc.perform(delete("/api/v1/tasks/{taskId}", TASK_ID))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void updateTaskUsesPatchRoute() throws Exception {
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("NEW"));
        when(taskAdmin.updateTaskDefinition(any(), any())).thenReturn(true);

        mockMvc.perform(patch("/api/v1/tasks/{taskId}", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "sharedConfig":{"routingCode":"us"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Task updated"));
    }

    private TaskShellSnapshot taskShell(String taskId, String project, String userId) {
        return new TaskShellSnapshot(taskId, "task-name", "default", project, userId, null, null);
    }

    private TaskStateSnapshot taskState(String status) {
        return taskState(status, "OPEN");
    }

    private TaskStateSnapshot taskState(String status, String intakeStatus) {
        return new TaskStateSnapshot(TASK_ID, status, null, intakeStatus);
    }

    private PrincipalContext operatorPrincipal() {
        return new PrincipalContext(
                "operator-1",
                PrincipalType.SERVICE,
                "operator-user",
                null,
                List.of("*", "task:edit", "task:govern", "task:control"),
                List.of("*"),
                List.of("*"),
                Map.of()
        );
    }

    private TaskAccessSnapshot taskAccess(String project) {
        return new TaskAccessSnapshot(TASK_ID, project, Map.of(), "OPEN");
    }

    private TaskReviewStoreTaskReviewReadModel reviewReadModel() {
        return new TaskReviewStoreTaskReviewReadModel(taskReviewStore);
    }

    private TaskReviewReadModelWriter directReviewWriter() {
        TaskReviewStoreMaterializer materializer = new TaskReviewStoreMaterializer(taskReviewStore);
        return new TaskReviewReadModelWriter() {
            @Override
            public void recordItemsAccepted(String taskId,
                                            Map<String, Object> sharedConfig,
                                            List<Map<String, Object>> acceptedItems,
                                            TaskItemBatchAppendReceipt receipt,
                                            int maxRetryCount) {
                materializer.apply(TaskReviewItemsAcceptedEvent.from(taskId, acceptedItems, receipt, maxRetryCount));
            }

            @Override
            public void recordAttemptClosed(com.xa.mass.sdk.model.TaskWorkAttemptClosedNotification notification) {
                materializer.apply(TaskReviewAttemptClosedEvent.from(notification));
            }

            @Override
            public void recordWorkFinal(com.xa.mass.sdk.model.TaskWorkFinalNotification notification) {
                materializer.apply(TaskReviewWorkTerminalEvent.from(notification));
            }
        };
    }

    private TaskAccessSnapshot taskAccessOwned(String project, String userId) {
        return new TaskAccessSnapshot(
                TASK_ID,
                project,
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of(),
                        new TaskOwnershipStamp(userId, PrincipalType.SERVICE)
                ),
                "OPEN"
        );
    }

    private com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem reviewItem(String messageId,
                                                                                 String status,
                                                                                 String finalReason,
                                                                                 Map<String, Object> input,
                                                                                 Map<String, Object> output,
                                                                                 String workerId) {
        return new com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem(
                messageId,
                input == null ? null : String.valueOf(input.get("eventCode")),
                status,
                finalReason,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                null,
                input,
                workerId,
                "batch-" + messageId,
                "attempt-" + messageId,
                null,
                null,
                output
        );
    }

    private TaskResultItemSnapshot resultRow(long seq, String messageId, String status, String workerId) {
        return new TaskResultItemSnapshot(
                seq,
                messageId,
                "crawler.fetch-page",
                status,
                "SUCCESS".equals(status) ? "BUSINESS_SUCCESS" : "RETRY_EXHAUSTED",
                0,
                3,
                workerId,
                "batch-001",
                "attempt-001",
                "payload-ref",
                Instant.parse("2026-05-13T00:00:00Z"),
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:02Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                "FAILED".equals(status) ? "ERR" : null,
                "FAILED".equals(status) ? "failed" : null,
                Map.of("messageId", messageId)
        );
    }

    private TaskDetailSnapshot taskDetail(String status, String taskName, String project) {
        return new TaskDetailSnapshot(
                TASK_ID,
                "default",
                taskName,
                null,
                project,
                status,
                0,
                0,
                0,
                0,
                0,
                0,
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("routingCode", "us"),
                        new TaskOwnershipStamp("agent", PrincipalType.SERVICE)
                ),
                null,
                new TaskExecutionOptions(),
                null,
                "OPEN",
                "agent",
                null,
                null,
                null,
                null,
                null
        );
    }

    private ControlPlaneCatalog createTaskCatalog() {
        ProjectEventCatalogRegistry catalog = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerProject(ProjectDefinition.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        catalog.registerProject(ProjectDefinition.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Test crawler app")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        return catalog;
    }
}
