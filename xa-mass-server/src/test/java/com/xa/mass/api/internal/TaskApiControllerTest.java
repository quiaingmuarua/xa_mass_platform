package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.SdkTaskMessageSnapshot;
import com.xa.mass.sdk.SdkTaskMessageView;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskMessageQueryOperations;
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
    private TaskMessageQueryOperations taskMessageQueries;

    @Mock
    private TaskAdminOperations taskAdmin;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TaskApiController(taskQueries, taskMessageQueries, taskAdmin, createTaskCatalog(), authProvider)
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
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
                                  "workloadClass":"INTERACTIVE",
                                  "sharedConfig":{"textContent":"hello"},
                                  "userId":"agent",
                                  "batchSize":2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID));

        ArgumentCaptor<MassTaskShellCreateRequest> captor = ArgumentCaptor.forClass(MassTaskShellCreateRequest.class);
        verify(taskAdmin).createTaskShell(captor.capture());
        MassTaskShellCreateRequest request = captor.getValue();
        assertEquals("smoke-create", request.getTaskName());
        assertEquals("demoApp", request.getProject());
        assertEquals("agent", request.getUserId());
        assertEquals(2, request.getBatchSize());
        assertEquals(TaskWorkloadClass.INTERACTIVE, request.getWorkloadClass());
        assertNull(request.getEventCode());
    }

    @Test
    void createTaskShellRejectsLegacyInputsField() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"legacy-create",
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
                                  "taskName":"sdk-crawler",
                                  "eventCode":"crawler.fetch-page"
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
        when(taskQueries.validateTaskState(TASK_ID)).thenReturn(Map.of("ok", true));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task.tid").value(TASK_ID))
                .andExpect(jsonPath("$.data.task.taskName").value("detail-task"))
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void appendTaskItemsUsesStoredTextPayloadTypeAndRetrySeed() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setSharedConfig(Map.of("_sdk", Map.of(
                "eventCode", "chatbot.reply",
                "payloadType", "TEXT",
                "taskMode", "STREAMING"
        )));

        when(taskQueries.getTask(TASK_ID)).thenReturn(task);
        when(taskAdmin.appendTaskItems(any(), any(MassTaskItemBatchAppendRequest.class))).thenReturn(2);

        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "items":["hello","world"],
                                  "defaultMsgMaxRetryCount":5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(2));

        ArgumentCaptor<MassTaskItemBatchAppendRequest> captor =
                ArgumentCaptor.forClass(MassTaskItemBatchAppendRequest.class);
        verify(taskAdmin).appendTaskItems(org.mockito.ArgumentMatchers.eq(TASK_ID), captor.capture());
        assertEquals(List.of("hello", "world"), captor.getValue().getItems());
        assertEquals(5, captor.getValue().getDefaultMsgMaxRetryCount());
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
    void getTaskItemsReturnsBoundedSnapshot() throws Exception {
        when(taskMessageQueries.getTaskMessageSnapshot(TASK_ID, 100))
                .thenReturn(new SdkTaskMessageSnapshot(List.of(
                        new SdkTaskMessageView(
                                "msg-1", TASK_ID, "INIT", null, null, null, null, 0, 3,
                                null, null, null, null, Map.of("target", "alpha"), Map.of("result", "ok"),
                                null, null, null, null, null
                        )
                ), 100, false));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/items", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(100))
                .andExpect(jsonPath("$.data.returned").value(1))
                .andExpect(jsonPath("$.data.messages[0].messageId").value("msg-1"))
                .andExpect(jsonPath("$.data.messages[0].output.result").value("ok"));
    }

    @Test
    void projectionAuditUsesExplicitRoute() throws Exception {
        when(taskQueries.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskQueries.auditTaskProjectionState(TASK_ID)).thenReturn(Map.of("projectionHealthy", true));

        mockMvc.perform(get("/api/v1/tasks/{taskId}:projection-audit", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectionAudit.projectionHealthy").value(true));
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
                                  "taskName":"patched-task",
                                  "batchSize":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("Task updated"));
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
        return catalog;
    }
}
