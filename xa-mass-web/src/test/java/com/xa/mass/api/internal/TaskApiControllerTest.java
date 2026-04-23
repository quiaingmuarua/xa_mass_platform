package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.SdkEventDefinition;
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
    private TaskOperations taskOperations;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProjectEventCatalog catalog = createTaskCatalog();
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskApiController(taskOperations, catalog, authProvider)).build();
    }

    @Test
    void auditApprovesNewTaskThroughSdkFacade() throws Exception {
        Task newTask = taskWithStatus(TaskStatus.NEW);
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskOperations.getTask(TASK_ID)).thenReturn(newTask, readyTask);
        when(taskOperations.approveTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/audit", TASK_ID)
                        .param("approved", "true")
                        .param("comment", "smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.newStatus").value("READY"))
                .andExpect(jsonPath("$.data.message").value("Task approved"));

        verify(taskOperations).approveTask(TASK_ID);
        verify(taskOperations, never()).rejectTask(TASK_ID);
    }

    @Test
    void pauseReturnsSuccessWhenSdkAllowsIt() throws Exception {
        when(taskOperations.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskOperations.pauseTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/pause", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task paused"));
    }

    @Test
    void resumeReturnsSuccessWhenSdkAllowsIt() throws Exception {
        when(taskOperations.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskOperations.resumeTaskDetailed(TASK_ID))
                .thenReturn(new SdkTaskResumeResult(true, "READY", null, false));

        mockMvc.perform(post("/status/api/tasks/{taskId}/resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task resumed"))
                .andExpect(jsonPath("$.data.newStatus").value("READY"));
    }

    @Test
    void resumeReturnsTerminalWhenPausedTaskAlreadyCompleted() throws Exception {
        when(taskOperations.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskOperations.resumeTaskDetailed(TASK_ID))
                .thenReturn(new SdkTaskResumeResult(true, "TERMINAL", TaskTerminalReason.ALL_MESSAGES_SUCCEEDED.name(), true));

        mockMvc.perform(post("/status/api/tasks/{taskId}/resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task already completed while paused and was closed to TERMINAL"))
                .andExpect(jsonPath("$.data.newStatus").value("TERMINAL"))
                .andExpect(jsonPath("$.data.terminalReason").value("ALL_MESSAGES_SUCCEEDED"));
    }

    @Test
    void createTaskReturnsTaskIdAndDelegatesRequestToSdk() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);

        when(taskOperations.createTask(any(MassTaskCreateRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
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
        verify(taskOperations).createTask(captor.capture());
        MassTaskCreateRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("smoke-create", request.getTaskName());
        org.junit.jupiter.api.Assertions.assertEquals("demoApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("hello", request.getSharedConfig().get("textContent"));
        org.junit.jupiter.api.Assertions.assertEquals("agent", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(2, request.getBatchSize());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                Map.of("target", "alpha"),
                Map.of("target", "beta")
        ), request.getInputs());
    }

    @Test
    void createTaskWithSdkFieldsDelegatesToSdkModeRequest() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);
        createdTask.setTid("task-sdk-001");

        when(taskOperations.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"sdk-crawler",
                                  "project":"demoApp",
                                  "eventCode":"crawler.fetch-page",
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
        verify(taskOperations).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("sdk-crawler", request.getTaskName());
        org.junit.jupiter.api.Assertions.assertEquals("demoApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("crawler.fetch-page", request.getEventCode());
        org.junit.jupiter.api.Assertions.assertEquals("example", request.getSharedConfig().get("site"));
        org.junit.jupiter.api.Assertions.assertTrue(request.isStreaming());
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

        when(authProvider.authenticate("sdk-key")).thenReturn(new TaskSubmitterContext(
                "crawler-agent",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of("transport", "polling")
        ));
        when(taskOperations.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

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
        verify(taskOperations).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("crawlerApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertEquals("crawler-agent", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals("crawler.fetch-page", request.getEventCode());
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
        verify(taskOperations, never()).createTask(any(MassTaskCreateRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsProjectScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new TaskSubmitterContext(
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsUserScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new TaskSubmitterContext(
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsMissingCreatePermission() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new TaskSubmitterContext(
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void createTaskWithSdkCredentialRejectsEventScopeViolation() throws Exception {
        when(authProvider.authenticate("sdk-key")).thenReturn(new TaskSubmitterContext(
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
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

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
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

        verify(taskOperations, never()).createTask(any(MassTaskCreateRequest.class));
    }

    @Test
    void getTaskReturnsTaskAndMaterializedItems() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);
        task.setProject("demoApp");
        task.setUser(com.xa.mass.base.model.UserRef.of("agent-1"));

        when(taskOperations.getTask(TASK_ID)).thenReturn(task);
        when(taskOperations.getTaskMessages(TASK_ID)).thenReturn(List.of(
                new TaskMsg("msg-1", TASK_ID, Map.of("target", "alpha")),
                new TaskMsg("msg-2", TASK_ID, Map.of("target", "beta"))
        ));
        when(taskOperations.validateTaskState(TASK_ID)).thenReturn(Map.of(
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
                .andExpect(jsonPath("$.data.items[0].target").value("alpha"))
                .andExpect(jsonPath("$.data.items[1].target").value("beta"))
                .andExpect(jsonPath("$.data.compatTargetList").doesNotExist())
                .andExpect(jsonPath("$.data.stateValidation.valid").value(true))
                .andExpect(jsonPath("$.data.stateValidation.needsResolution").value(false))
                .andExpect(jsonPath("$.data.stateValidation.status").value("READY"));
    }

    @Test
    void deleteTaskReturnsSuccessWhenTaskExists() throws Exception {
        when(taskOperations.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW));
        when(taskOperations.deleteTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(delete("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task deleted"));
    }

    @Test
    void updateTaskMutatesExistingTaskAndDelegatesToSdk() throws Exception {
        Task existingTask = taskWithStatus(TaskStatus.NEW);
        existingTask.setUser(com.xa.mass.base.model.UserRef.of("before"));
        when(taskOperations.getTask(TASK_ID)).thenReturn(existingTask);

        mockMvc.perform(put("/status/api/tasks/{taskId}", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"updated-name",
                                  "project":"testApp",
                                  "sharedConfig":{"textContent":"updated-content"},
                                  "userId":"updated-user",
                                  "batchSize":5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task updated"));

        verify(taskOperations).updateTask(argThat(task ->
                TASK_ID.equals(task.getTid())
                        && "updated-name".equals(task.getTaskName())
                        && "testApp".equals(task.getProject())
                        && "updated-content".equals(task.getSharedConfig() != null ? task.getSharedConfig().get("textContent") : null)
                        && task.getUser() != null
                        && "updated-user".equals(task.getUser().getUserId())
                        && task.getBatchSize() == 5
        ));
    }

    @Test
    void getTaskMessagesReturnsPagedMessages() throws Exception {
        TaskMsg first = new TaskMsg("msg-1", TASK_ID, Map.of("target", "alpha"));
        first.setOutput(Map.of("result", "ok"));
        TaskMsg second = new TaskMsg("msg-2", TASK_ID, Map.of("target", "beta"));
        when(taskOperations.getTaskMessages(TASK_ID)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/status/api/tasks/{taskId}/messages", TASK_ID)
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.messages[0].msgId").value("msg-1"))
                .andExpect(jsonPath("$.data.messages[0].input.target").value("alpha"))
                .andExpect(jsonPath("$.data.messages[0].output.result").value("ok"));
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

        when(taskOperations.getTask(TASK_ID)).thenReturn(task);
        when(taskOperations.appendTaskItems(any(), any())).thenReturn(2);

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

        verify(taskOperations).appendTaskItems(TASK_ID, List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "text", "text", "world")
        ));
    }

    @Test
    void sealTaskDelegatesToSdkFacade() throws Exception {
        Task task = taskWithStatus(TaskStatus.RUNNING);
        when(taskOperations.getTask(TASK_ID)).thenReturn(task);
        when(taskOperations.sealTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(put("/status/api/tasks/{taskId}/seal", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.message").value("Task sealed"));

        verify(taskOperations).sealTask(TASK_ID);
    }

    private Task taskWithStatus(TaskStatus status) {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setStatus(status);
        return task;
    }

    private ProjectEventCatalog createTaskCatalog() {
        ProjectEventCatalogRegistry catalog = DefaultProjectEventCatalogFactory.createDefaultRegistry();
        catalog.registerEventDefinition(SdkEventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerEventDefinition(SdkEventDefinition.builder()
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
