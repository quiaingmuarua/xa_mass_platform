package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TaskApiControllerTest {

    private static final String TASK_ID = "task-001";

    @Mock
    private TaskQueryOperations taskQueries;

    @Mock
    private TaskAdminOperations taskAdmin;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProjectEventCatalog catalog = createTaskCatalog();
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskApiController(taskQueries, taskAdmin, catalog, authProvider)).build();
    }

    @Test
    void auditApprovesNewTaskThroughSdkFacade() throws Exception {
        Task newTask = taskWithStatus(TaskStatus.NEW);
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskQueries.getTask(TASK_ID)).thenReturn(newTask, readyTask);
        when(taskAdmin.approveTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/audit", TASK_ID)
                        .param("approved", "true")
                        .param("comment", "smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.newStatus").value("READY"))
                .andExpect(jsonPath("$.data.message").value("Task approved"));

        verify(taskAdmin).approveTask(TASK_ID);
        verify(taskAdmin, never()).rejectTask(TASK_ID);
    }

    @Test
    void pauseReturnsSuccessWhenSdkAllowsIt() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskAdmin.pauseTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/pause", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task paused"));
    }

    @Test
    void resumeReturnsSuccessWhenSdkAllowsIt() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskAdmin.resumeTaskDetailed(TASK_ID))
                .thenReturn(new SdkTaskResumeResult(true, "READY", null, false));

        mockMvc.perform(post("/status/api/tasks/{taskId}/resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task resumed"))
                .andExpect(jsonPath("$.data.newStatus").value("READY"));
    }

    @Test
    void resumeReturnsTerminalWhenPausedTaskAlreadyCompleted() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskAdmin.resumeTaskDetailed(TASK_ID))
                .thenReturn(new SdkTaskResumeResult(true, "TERMINAL", TaskTerminalReason.ALL_MESSAGES_SUCCEEDED.name(), true));

        mockMvc.perform(post("/status/api/tasks/{taskId}/resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task already completed while paused and was closed to TERMINAL"))
                .andExpect(jsonPath("$.data.newStatus").value("TERMINAL"))
                .andExpect(jsonPath("$.data.terminalReason").value("ALL_MESSAGES_SUCCEEDED"));
    }

    @Test
    void terminateDelegatesToExplicitTerminateCommand() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.RUNNING));
        when(taskAdmin.terminateTask(TASK_ID, TaskTerminalReason.MANUAL_CANCELLED)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/terminate", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task terminated"));

        verify(taskAdmin).terminateTask(TASK_ID, TaskTerminalReason.MANUAL_CANCELLED);
        verify(taskAdmin, never()).cancelTask(TASK_ID);
    }

    @Test
    void createTaskReturnsTaskIdAndDelegatesRequestToSdk() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);

        when(taskAdmin.createTask(any(MassTaskCreateRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
                                  "workloadClass":"INTERACTIVE",
                                  "sharedConfig":{"textContent":"hello"},
                                  "userId":"agent",
                                  "inputs":[{"target":"alpha"},{"target":"beta"}],
                                  "batchSize":2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.message").value("Task created"));

        ArgumentCaptor<MassTaskCreateRequest> captor = ArgumentCaptor.forClass(MassTaskCreateRequest.class);
        verify(taskAdmin).createTask(captor.capture());
        MassTaskCreateRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("smoke-create", request.getTaskName());
        org.junit.jupiter.api.Assertions.assertEquals("demoApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("hello", request.getSharedConfig().get("textContent"));
        org.junit.jupiter.api.Assertions.assertEquals("agent", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(2, request.getBatchSize());
        org.junit.jupiter.api.Assertions.assertEquals(TaskWorkloadClass.INTERACTIVE, request.getWorkloadClass());
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(request.getSharedConfig());
        org.junit.jupiter.api.Assertions.assertNotNull(ownershipStamp);
        org.junit.jupiter.api.Assertions.assertEquals("ops-admin", ownershipStamp.getCreatedByPrincipalId());
        org.junit.jupiter.api.Assertions.assertEquals(PrincipalType.OPERATOR, ownershipStamp.getCreatedByPrincipalType());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                Map.of("target", "alpha"),
                Map.of("target", "beta")
        ), request.getInputs());
    }

    @Test
    void createTaskWithSdkFieldsDelegatesToSdkModeRequest() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);
        createdTask.setTid("task-sdk-001");

        when(taskAdmin.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "project":"demoApp",
                                  "eventCode":"crawler.fetch-page",
                                  "workloadClass":"BULK",
                                  "mode":"STREAMING",
                                  "payloadType":"JSON",
                                  "sharedConfig":{"site":"example"},
                                  "userId":"agent",
                                  "inputs":[{"url":"https://example.test"}],
                                  "batchSize":1,
                                  "defaultMsgMaxRetryCount":2,
                                  "maxRuntimeSeconds":60
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-sdk-001"))
                .andExpect(jsonPath("$.data.message").value("Task created"));

        ArgumentCaptor<MassTaskRequest> captor = ArgumentCaptor.forClass(MassTaskRequest.class);
        verify(taskAdmin).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("sdk-crawler", request.getTaskName());
        org.junit.jupiter.api.Assertions.assertEquals("demoApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("crawler.fetch-page", request.getEventCode());
        org.junit.jupiter.api.Assertions.assertEquals("example", request.getSharedConfig().get("site"));
        org.junit.jupiter.api.Assertions.assertTrue(request.isStreaming());
        org.junit.jupiter.api.Assertions.assertEquals(TaskWorkloadClass.BULK, request.getWorkloadClass());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                Map.of("type", "json", "data", Map.of("url", "https://example.test"))
        ), request.toEngineInputs());
    }

    @Test
    void createTaskWithSdkCredentialUsesSubmitterScopeAndDelegatesToSdkModeRequest() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);
        createdTask.setTid("task-sdk-002");
        createdTask.setProject("crawlerApp");
        createdTask.setUser(com.xa.mass.base.model.UserRef.of("crawler-agent"));

        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-agent",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of("transport", "polling")
        ));
        when(taskAdmin.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "eventCode":"crawler.fetch-page",
                                  "payloadType":"JSON",
                                  "sharedConfig":{"source":"sdk"},
                                  "inputs":[{"url":"https://example.test"}],
                                  "batchSize":1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-sdk-002"))
                .andExpect(jsonPath("$.data.project").value("crawlerApp"))
                .andExpect(jsonPath("$.data.userId").value("crawler-agent"))
                .andExpect(jsonPath("$.data.principalId").value("crawler-agent"));

        ArgumentCaptor<MassTaskRequest> captor = ArgumentCaptor.forClass(MassTaskRequest.class);
        verify(taskAdmin).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("crawlerApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("crawler-agent", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals("crawler.fetch-page", request.getEventCode());
        TaskOwnershipStamp ownershipStamp = TaskOwnershipStamp.fromSharedConfig(request.getSharedConfig());
        org.junit.jupiter.api.Assertions.assertNotNull(ownershipStamp);
        org.junit.jupiter.api.Assertions.assertEquals("crawler-agent", ownershipStamp.getCreatedByPrincipalId());
        org.junit.jupiter.api.Assertions.assertEquals(PrincipalType.SERVICE, ownershipStamp.getCreatedByPrincipalType());
    }

    @Test
    void createTaskWithSdkCredentialRejectsInvalidCredential() throws Exception {
        when(authProvider.authenticate("bad-key")).thenReturn(null);

        mockMvc.perform(post("/status/api/tasks")
                        .header("X-Mass-Api-Key", "bad-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "eventCode":"crawler.fetch-page",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("Invalid or missing SDK credential"));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
        verify(taskAdmin, never()).createTask(any(MassTaskCreateRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsProjectScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "telegram-bot",
                "bot-user",
                "telegramApp",
                List.of("task:create"),
                List.of("telegramApp"),
                List.of("chatbot.reply"),
                Map.of()
        ));

        mockMvc.perform(post("/status/api/tasks")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "project":"crawlerApp",
                                  "eventCode":"chatbot.reply",
                                  "payloadType":"TEXT",
                                  "inputs":["hello"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential project scope denied: crawlerApp"));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsUserScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "telegram-bot",
                "bot-user",
                "telegramApp",
                List.of("task:create"),
                List.of("telegramApp"),
                List.of("chatbot.reply"),
                Map.of()
        ));

        mockMvc.perform(post("/status/api/tasks")
                        .header("Authorization", "Bearer sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "eventCode":"chatbot.reply",
                                  "payloadType":"TEXT",
                                  "userId":"another-user",
                                  "inputs":["hello"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential user scope denied: another-user"));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsMissingCreatePermission() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-key",
                "crawler-agent",
                "crawlerApp",
                List.of("metadata:view"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of()
        ));

        mockMvc.perform(post("/status/api/tasks")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "eventCode":"crawler.fetch-page",
                                  "payloadType":"JSON",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential permission denied: task:create"));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsEventScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-key",
                "crawler-agent",
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.parse-result"),
                Map.of()
        ));

        mockMvc.perform(post("/status/api/tasks")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "eventCode":"crawler.fetch-page",
                                  "payloadType":"JSON",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("SDK credential event scope denied: crawler.fetch-page"));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskRejectsUnsupportedProjectEventBinding() throws Exception {
        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"rcsApp",
                                  "taskName":"bad-event",
                                  "eventCode":"crawler.fetch-page",
                                  "mode":"SINGLE_RUN",
                                  "payloadType":"JSON",
                                  "userId":"agent",
                                  "inputs":[{"target":"x"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(taskAdmin, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskRejectsUnknownFields() throws Exception {
        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
                                  "sharedConfig":{"textContent":"hello"},
                                  "userId":"agent",
                                  "inputs":[{"target":"alpha"}],
                                  "targetJsonList":["{\\"phone\\":\\"1\\"}"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("Task create failed: Unsupported task create fields: targetJsonList"));

        verify(taskAdmin, never()).createTask(any(MassTaskCreateRequest.class));
    }

    @Test
    void getTaskReturnsTaskAndMaterializedItems() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setProject("demoApp");
        task.setUser(com.xa.mass.base.model.UserRef.of("agent-1"));
        task.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
        ));

        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
        when(taskQueries.countTaskMessages(TASK_ID)).thenReturn(2L);
        when(taskQueries.getTaskMessages(eq(TASK_ID), anyInt())).thenReturn(List.of(
                new TaskMsg("msg-1", TASK_ID, Map.of("target", "alpha")),
                new TaskMsg("msg-2", TASK_ID, Map.of("target", "beta"))
        ));
        when(taskQueries.validateTaskState(TASK_ID)).thenReturn(Map.of(
                "valid", true,
                "needsResolution", false,
                "status", "READY"
        ));

        mockMvc.perform(get("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.task.tid").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.status").value("READY"))
                .andExpect(jsonPath("$.data.task.project").value("demoApp"))
                .andExpect(jsonPath("$.data.task.user.userId").value("agent-1"))
                .andExpect(jsonPath("$.data.task.sharedConfig.source").value("sdk"))
                .andExpect(jsonPath("$.data.task.sharedConfig._massSecurity").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].target").value("alpha"))
                .andExpect(jsonPath("$.data.items[1].target").value("beta"))
                .andExpect(jsonPath("$.data.itemsTotal").value(2))
                .andExpect(jsonPath("$.data.itemsLimit").value(100))
                .andExpect(jsonPath("$.data.itemsTruncated").value(false))
                .andExpect(jsonPath("$.data.security.createdByPrincipalId").value("crawler-agent"))
                .andExpect(jsonPath("$.data.security.createdByPrincipalType").value("SERVICE"))
                .andExpect(jsonPath("$.data.compatTargetList").doesNotExist())
                .andExpect(jsonPath("$.data.stateValidation.valid").value(true))
                .andExpect(jsonPath("$.data.stateValidation.needsResolution").value(false))
                .andExpect(jsonPath("$.data.stateValidation.status").value("READY"));
    }

    @Test
    void getTaskProjectionAuditReturnsExplicitDiagnosticSurface() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
        when(taskQueries.auditTaskProjectionState(TASK_ID)).thenReturn(Map.of(
                "valid", false,
                "scope", "PROJECTION_AUDIT",
                "violations", List.of("ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE")
        ));

        mockMvc.perform(get("/status/api/tasks/{taskId}/projection-audit", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.projectionAudit.valid").value(false))
                .andExpect(jsonPath("$.data.projectionAudit.scope").value("PROJECTION_AUDIT"))
                .andExpect(jsonPath("$.data.projectionAudit.violations[0]").value("ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE"));

        verify(taskQueries).auditTaskProjectionState(TASK_ID);
    }

    @Test
    void listTasksExposesDerivedSecurityView() throws Exception {
        Task task = taskWithStatus(TaskStatus.NEW);
        task.setTaskName("secured-task");
        task.setProject("demoApp");
        task.setUser(com.xa.mass.base.model.UserRef.of("agent-1"));
        task.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("ops-admin", PrincipalType.OPERATOR)
        ));

        when(taskQueries.listTasksPaged(0, 500)).thenReturn(List.of(task));

        mockMvc.perform(get("/status/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(TASK_ID))
                .andExpect(jsonPath("$.data.items[0].security.createdByPrincipalId").value("ops-admin"))
                .andExpect(jsonPath("$.data.items[0].security.createdByPrincipalType").value("OPERATOR"));
    }

    @Test
    void listTasksWithSdkCredentialFiltersToOwnedTasks() throws Exception {
        Task ownedTask = taskWithStatus(TaskStatus.NEW);
        ownedTask.setTaskName("owned-task");
        ownedTask.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("crawler-agent", PrincipalType.SERVICE)
        ));
        Task foreignTask = taskWithStatus(TaskStatus.NEW);
        foreignTask.setTid("task-foreign-001");
        foreignTask.setTaskName("foreign-task");
        foreignTask.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("other-agent", PrincipalType.SERVICE)
        ));

        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-agent",
                "crawler-user",
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of()
        ));
        when(taskQueries.listTasksPaged(0, 500)).thenReturn(List.of(ownedTask, foreignTask));

        mockMvc.perform(get("/status/api/tasks")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskName").value("owned-task"));
    }

    @Test
    void getTaskWithSdkCredentialRejectsOwnerMismatch() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("other-agent", PrincipalType.SERVICE)
        ));

        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-agent",
                "crawler-user",
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of()
        ));
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);

        mockMvc.perform(get("/status/api/tasks/{taskId}", TASK_ID)
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("SDK credential owner mismatch: other-agent"));
    }

    @Test
    void getTaskMessagesWithSdkCredentialRejectsOwnerMismatch() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setSharedConfig(TaskOwnershipStamp.applyToSharedConfig(
                Map.of("source", "sdk"),
                new TaskOwnershipStamp("other-agent", PrincipalType.SERVICE)
        ));

        when(authProvider.authenticate("sdk-key")).thenReturn(new PrincipalContext(
                "crawler-agent",
                "crawler-user",
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of()
        ));
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);

        mockMvc.perform(get("/status/api/tasks/{taskId}/messages", TASK_ID)
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("SDK credential owner mismatch: other-agent"));
    }

    @Test
    void deleteTaskReturnsSuccessWhenTaskExists() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW));
        when(taskAdmin.deleteTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(delete("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task deleted"));
    }

    @Test
    void updateTaskMutatesExistingTaskAndDelegatesToSdk() throws Exception {
        Task existingTask = taskWithStatus(TaskStatus.NEW);
        existingTask.setUser(com.xa.mass.base.model.UserRef.of("before"));
        when(taskQueries.getTask(TASK_ID)).thenReturn(existingTask);

        mockMvc.perform(put("/status/api/tasks/{taskId}", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"updated-name",
                                  "project":"testApp",
                                  "sharedConfig":{
                                    "textContent":"updated-content",
                                    "_massSecurity":{
                                      "createdByPrincipalId":"forged-owner",
                                      "createdByPrincipalType":"SERVICE"
                                    }
                                  },
                                  "userId":"updated-user",
                                  "batchSize":5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task updated"));

        verify(taskAdmin).updateTaskDefinition(eq(TASK_ID), argThat(request ->
                "updated-name".equals(request.getTaskName())
                        && "testApp".equals(request.getProject())
                        && "updated-content".equals(request.getSharedConfig() != null ? request.getSharedConfig().get("textContent") : null)
                        && (request.getSharedConfig() == null || !request.getSharedConfig().containsKey(TaskOwnershipStamp.SHARED_CONFIG_KEY))
                        && "updated-user".equals(request.getUserId())
                        && Integer.valueOf(5).equals(request.getBatchSize())
        ));
    }

    @Test
    void getTaskMessagesReturnsCompatibilitySnapshot() throws Exception {
        TaskMsg first = new TaskMsg("msg-1", TASK_ID, Map.of("target", "alpha"));
        first.setOutput(Map.of("result", "ok"));
        TaskMsg second = new TaskMsg("msg-2", TASK_ID, Map.of("target", "beta"));
        when(taskQueries.countTaskMessages(TASK_ID)).thenReturn(2L);
        when(taskQueries.getTaskMessages(TASK_ID, 100)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/status/api/tasks/{taskId}/messages", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.limit").value(100))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.messages[0].messageId").value("msg-1"))
                .andExpect(jsonPath("$.data.messages[0].input.target").value("alpha"))
                .andExpect(jsonPath("$.data.messages[0].output.result").value("ok"))
                .andExpect(jsonPath("$.data.messages[1].messageId").value("msg-2"));

        verify(taskQueries).getTaskMessages(TASK_ID, 100);
        verify(taskQueries).countTaskMessages(TASK_ID);
    }

    @Test
    void getTaskMessagesCapsRequestedLimitWithoutPagination() throws Exception {
        when(taskQueries.countTaskMessages(TASK_ID)).thenReturn(1_000L);
        when(taskQueries.getTaskMessages(TASK_ID, 500))
                .thenReturn(List.of(new TaskMsg("msg-1", TASK_ID, Map.of("target", "alpha"))));

        mockMvc.perform(get("/status/api/tasks/{taskId}/messages", TASK_ID)
                        .param("limit", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1000))
                .andExpect(jsonPath("$.data.limit").value(500))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.page").doesNotExist())
                .andExpect(jsonPath("$.data.size").doesNotExist());

        verify(taskQueries).getTaskMessages(TASK_ID, 500);
    }

    @Test
    void appendTaskItemsUsesStoredTextPayloadType() throws Exception {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setSharedConfig(Map.of("_sdk", Map.of(
                "eventCode", "chatbot.reply",
                "payloadType", "TEXT",
                "taskMode", "STREAMING"
        )));

        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
        when(taskAdmin.appendTaskItems(any(), any())).thenReturn(2);

        mockMvc.perform(post("/status/api/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "inputs":["hello","world"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.added").value(2));

        verify(taskAdmin).appendTaskItems(TASK_ID, List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "text", "text", "world")
        ));
    }

    @Test
    void sealTaskDelegatesToSdkFacade() throws Exception {
        Task task = taskWithStatus(TaskStatus.RUNNING);
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
        when(taskAdmin.sealTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(put("/status/api/tasks/{taskId}/seal", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task sealed"));

        verify(taskAdmin).sealTask(TASK_ID);
    }

    private Task taskWithStatus(TaskStatus status) {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setStatus(status);
        return task;
    }

    private ProjectEventCatalog createTaskCatalog() {
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
        catalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Test crawler app")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("telegramApp")
                .name("Telegram App")
                .description("Test telegram app")
                .eventCodes(List.of("chatbot.reply"))
                .build());
        return catalog;
    }
}
