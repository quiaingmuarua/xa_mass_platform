package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskApiControllerTest {

    private static final String TASK_ID = "task-001";

    @Mock
    private TaskOperations taskOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskApiController(taskOperations)).build();
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
                                  "routingCode":"us",
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
        org.junit.jupiter.api.Assertions.assertEquals("us", request.getRoutingCode());
        org.junit.jupiter.api.Assertions.assertEquals("hello", request.getSharedConfig().get("textContent"));
        org.junit.jupiter.api.Assertions.assertEquals("agent", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(2, request.getBatchSize());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                Map.of("target", "alpha"),
                Map.of("target", "beta")
        ), request.getInputs());
    }

    @Test
    void createTaskRejectsUnknownFields() throws Exception {
        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
                                  "routingCode":"us",
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
                                  "project":"telegramApp",
                                  "routingCode":"sg",
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
                        && "telegramApp".equals(task.getProject())
                        && "sg".equals(task.getTaskRoutingCode())
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

    private Task taskWithStatus(TaskStatus status) {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setStatus(status);
        return task;
    }
}
