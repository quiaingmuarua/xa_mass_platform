package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TenantConstants;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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
    private TaskAdminOperations taskAdmin;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TaskApiController(taskQueries, taskAdmin, createTaskCatalog(), authProvider)
        ).build();
    }

    @Test
    void createTaskShellDelegatesToSdkShellCreate() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);
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
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID));

        ArgumentCaptor<MassTaskShellCreateRequest> captor = ArgumentCaptor.forClass(MassTaskShellCreateRequest.class);
        verify(taskAdmin).createTaskShell(captor.capture());
        MassTaskShellCreateRequest request = captor.getValue();
        assertEquals("demoApp", request.getProject());
        assertEquals(TenantConstants.DEFAULT_TENANT_ID, request.getTenantId());
        assertEquals("agent", request.getUserId());
        assertEquals(2, request.getExecutionSpec().getBatchSize());
        assertEquals(TaskWorkloadClass.INTERACTIVE, request.getExecutionSpec().getWorkloadClass());
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
        Task createdTask = taskWithStatus(TaskStatus.NEW);
        createdTask.setTid("task-sdk-001");
        createdTask.setProject("crawlerApp");
        createdTask.setUser(UserRef.of("crawler-agent"));

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
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW), taskWithStatus(TaskStatus.READY));
        when(taskAdmin.approveTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/api/v1/tasks/{taskId}:approve", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus").value("READY"));
    }

    @Test
    void resumeReturnsTerminalCloseMessageWhenAlreadyCompletedWhilePaused() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskAdmin.resumeTaskDetailed(TASK_ID))
                .thenReturn(new SdkTaskResumeResult(true, "TERMINAL", TaskTerminalReason.ALL_MESSAGES_SUCCEEDED.name(), true));

        mockMvc.perform(post("/api/v1/tasks/{taskId}:resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newStatus").value("TERMINAL"))
                .andExpect(jsonPath("$.data.terminalReason").value("ALL_MESSAGES_SUCCEEDED"));
    }

    @Test
    void getTaskDoesNotReturnItemsByDefault() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setTaskName("detail-task");
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);

        mockMvc.perform(get("/api/v1/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.tid").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.taskName").value("detail-task"))
                .andExpect(jsonPath("$.data.stateValidation").doesNotExist())
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void appendTaskItemsPassesBatchEventCode() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setProject("demoApp");

        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
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
        Task task = taskWithStatus(TaskStatus.READY);
        task.setProject("demoApp");
        when(taskQueries.getTask(TASK_ID)).thenReturn(task);

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
    void sealTaskUsesCommandRoute() throws Exception {
        when(taskAdmin.sealTask(TASK_ID)).thenReturn(true);
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.RUNNING));

        mockMvc.perform(post("/api/v1/tasks/{taskId}:seal", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Task sealed"));
    }

    @Test
    void deleteTaskUsesVersionedRoute() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW));
        when(taskAdmin.deleteTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v1/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Task deleted"));
    }

    @Test
    void updateTaskUsesPatchRoute() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW));
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

    private Task taskWithStatus(TaskStatus status) {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setStatus(status);
        task.setExecutionSpec(new TaskExecutionSpec());
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
        return catalog;
    }
}
