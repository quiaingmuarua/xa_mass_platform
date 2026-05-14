package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.TaskResultQueryOperations;
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
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskItemBatchAppendReceipt;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private TaskDetailStore taskDetailStore;

    @Mock
    private SyncTaskResultBridge syncTaskResultBridge;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TaskApiController(taskQueries, taskResultQueries, taskAdmin, createTaskCatalog(), taskDetailStore, authProvider, syncTaskResultBridge),
                new InternalTaskReviewController(taskQueries, taskDetailStore)
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
                                  "executionSpec":{"workloadClass":"INTERACTIVE","batchSize":2}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.project").value("demoApp"))
                .andExpect(jsonPath("$.data.task.execution.workloadClass").value("INTERACTIVE"))
                .andExpect(jsonPath("$.data.task.execution.batchSize").value(2))
                .andExpect(jsonPath("$.data.task.counters.successCount").value(0));

        ArgumentCaptor<MassTaskShellCreateRequest> captor = ArgumentCaptor.forClass(MassTaskShellCreateRequest.class);
        verify(taskAdmin).createTaskShell(captor.capture());
        MassTaskShellCreateRequest request = captor.getValue();
        assertEquals("demoApp", request.getProject());
        assertEquals("default", request.getTenantId());
        assertEquals("agent", request.getUserId());
        assertNull(request.getContract());
        assertEquals(2, request.getExecutionSpec().getBatchSize());
        assertEquals("INTERACTIVE", request.getExecutionSpec().getWorkloadClass());
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
    void createTaskShellWithSdkCredentialUsesSubmitterScope() throws Exception {
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
                .andExpect(jsonPath("$.data.task.sharedConfig").doesNotExist())
                .andExpect(jsonPath("$.data.security.createdByPrincipalId").value("agent"))
                .andExpect(jsonPath("$.data.security.createdByPrincipalType").value("SERVICE"))
                .andExpect(jsonPath("$.data.stateValidation").doesNotExist())
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void getTaskReviewReturnsSeedAndResultPreview() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        when(taskDetailStore.getTaskMessageStats(TASK_ID)).thenReturn(new TaskDetailStore.TaskMessageStats(2, 1, 0, 0, 1));
        when(taskDetailStore.getTaskMessageProjections(TASK_ID, 12)).thenReturn(List.of(
                new TaskDetailStore.TaskMessageProjection(
                        "msg-001",
                        TASK_ID,
                        Map.of("eventCode", "crawler.fetch-page", "url", "https://example.test/a"),
                        null,
                        TaskMessageProjectionStatus.SUCCESS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        3,
                        null,
                        null,
                        TaskMessageProjectionFinalReason.BUSINESS_SUCCESS,
                        Map.of("html", "<ok>"),
                        "attempt-001",
                        "worker-001",
                        "context-001",
                        "batch-001"
                )
        ));

        mockMvc.perform(get("/internal/v1/review/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.totalItems").value(2))
                .andExpect(jsonPath("$.data.seedPreview[0].eventCode").value("crawler.fetch-page"))
                .andExpect(jsonPath("$.data.resultPreview[0].workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.exports.seedUrl").value("/internal/v1/review/tasks/task-001/seed-export"));
    }

    @Test
    void exportTaskSeedsReturnsAttachmentPayload() throws Exception {
        when(taskQueries.getTaskDetail(TASK_ID)).thenReturn(taskDetail("RUNNING", "detail-task", "demoApp"));
        when(taskDetailStore.getTaskMessageStats(TASK_ID)).thenReturn(new TaskDetailStore.TaskMessageStats(1, 0, 0, 0, 1));
        when(taskDetailStore.getTaskMessageProjections(TASK_ID, 1)).thenReturn(List.of(
                new TaskDetailStore.TaskMessageProjection(
                        "msg-001",
                        TASK_ID,
                        Map.of("eventCode", "crawler.fetch-page", "url", "https://example.test/a"),
                        null,
                        TaskMessageProjectionStatus.ASSIGNED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        3,
                        null,
                        null,
                        null,
                        Map.of(),
                        "attempt-001",
                        "worker-001",
                        "context-001",
                        "batch-001"
                )
        ));

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
        when(taskAdmin.appendTaskItems(any(), any(MassTaskItemBatchAppendRequest.class))).thenReturn(2);

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
        verify(taskAdmin).appendTaskItems(org.mockito.ArgumentMatchers.eq(TASK_ID), captor.capture());
        assertEquals(List.of(Map.of("text", "hello"), Map.of("text", "world")), captor.getValue().getItems());
        assertEquals("chatbot.reply", captor.getValue().getEventCode());
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

        verify(taskAdmin, never()).appendTaskItems(any(), any(MassTaskItemBatchAppendRequest.class));
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

        verify(taskAdmin, never()).appendTaskItems(any(), any(MassTaskItemBatchAppendRequest.class));
    }

    @Test
    void appendTaskItemSyncReturnsStableFinalResult() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("READY", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-001")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-001")).thenReturn(future);
        when(syncTaskResultBridge.await(TASK_ID, "msg-001", future, 2000L))
                .thenReturn(Optional.of(new TaskWorkFinalSnapshot(
                        TASK_ID,
                        "msg-001",
                        "SUCCESS",
                        "BUSINESS_SUCCESS",
                        0,
                        null,
                        null,
                        null,
                        Map.of("ok", true)
                )));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"},
                                  "timeoutMs":2000
                                }
                                """))
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
    void appendTaskItemSyncReturnsTimeoutWithoutCancellingAppend() throws Exception {
        when(taskQueries.getTaskAccess(TASK_ID)).thenReturn(taskAccess("demoApp"));
        when(taskQueries.getTaskState(TASK_ID)).thenReturn(taskState("RUNNING", "OPEN"));
        when(taskAdmin.appendTaskItemsWithReceipt(any(), any(MassTaskItemBatchAppendRequest.class)))
                .thenReturn(new TaskItemBatchAppendReceipt(TASK_ID, 1, List.of("msg-009")));
        CompletableFuture<TaskWorkFinalSnapshot> future = new CompletableFuture<>();
        when(syncTaskResultBridge.register(TASK_ID, "msg-009")).thenReturn(future);
        when(syncTaskResultBridge.await(TASK_ID, "msg-009", future, 5000L))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items:sync", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode":"chatbot.reply",
                                  "item":{"text":"hello"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value("msg-009"))
                .andExpect(jsonPath("$.data.synced").value(false))
                .andExpect(jsonPath("$.data.timedOut").value(true))
                .andExpect(jsonPath("$.data.timeoutMs").value(5000));
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

    private TaskDetailStore.TaskMessageProjection projection(String messageId,
                                                             TaskMessageProjectionStatus status,
                                                             TaskMessageProjectionFinalReason finalReason,
                                                             Map<String, Object> input,
                                                             Map<String, Object> output,
                                                             String workerId) {
        return new TaskDetailStore.TaskMessageProjection(
                messageId,
                TASK_ID,
                input,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                finalReason,
                output,
                "attempt-" + messageId,
                workerId,
                "context-" + workerId,
                "batch-" + messageId
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
                "context-001",
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
