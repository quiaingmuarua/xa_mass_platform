package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    private TaskManager taskManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TaskApiController controller = new TaskApiController();
        ReflectionTestUtils.setField(controller, "taskManager", taskManager);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void auditApprovesNewTaskThroughTaskManager() throws Exception {
        Task newTask = taskWithStatus(TaskStatus.NEW);
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskManager.getTask(TASK_ID)).thenReturn(newTask, readyTask);
        when(taskManager.approveTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/audit", TASK_ID)
                        .param("approved", "true")
                        .param("comment", "smoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.newStatus").value("READY"))
                .andExpect(jsonPath("$.message").value("任务审核通过"));

        verify(taskManager).approveTask(TASK_ID);
        verify(taskManager, never()).rejectTask(TASK_ID);
    }

    @Test
    void auditRejectsOutOfStateAction() throws Exception {
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskManager.getTask(TASK_ID)).thenReturn(readyTask, readyTask);
        when(taskManager.rejectTask(TASK_ID)).thenReturn(false);

        mockMvc.perform(post("/status/api/tasks/{taskId}/audit", TASK_ID)
                        .param("approved", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("当前任务状态不允许审核"));
    }

    @Test
    void pauseReturnsSuccessWhenTaskManagerAllowsIt() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskManager.pauseTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/pause", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务已暂停"));

        verify(taskManager).pauseTask(TASK_ID);
    }

    @Test
    void resumeReturnsSuccessWhenTaskManagerAllowsIt() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.PAUSED));
        when(taskManager.resumeTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/resume", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务已恢复"));

        verify(taskManager).resumeTask(TASK_ID);
    }

    @Test
    void terminateReturnsSuccessWhenTaskManagerAllowsIt() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskManager.cancelTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(post("/status/api/tasks/{taskId}/terminate", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务已中止"));

        verify(taskManager).cancelTask(TASK_ID);
    }

    @Test
    void updateStatusUsesResumeWhenCurrentStatusIsPaused() throws Exception {
        Task pausedTask = taskWithStatus(TaskStatus.PAUSED);
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskManager.getTask(TASK_ID)).thenReturn(pausedTask, readyTask);
        when(taskManager.resumeTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(put("/status/api/tasks/{taskId}/status", TASK_ID)
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.newStatus").value("READY"));

        verify(taskManager).resumeTask(TASK_ID);
        verify(taskManager, never()).approveTask(TASK_ID);
    }

    @Test
    void updateStatusUsesApproveWhenCurrentStatusIsNew() throws Exception {
        Task newTask = taskWithStatus(TaskStatus.NEW);
        Task readyTask = taskWithStatus(TaskStatus.READY);

        when(taskManager.getTask(TASK_ID)).thenReturn(newTask, readyTask);
        when(taskManager.approveTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(put("/status/api/tasks/{taskId}/status", TASK_ID)
                        .param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.newStatus").value("READY"));

        verify(taskManager).approveTask(TASK_ID);
        verify(taskManager, never()).resumeTask(TASK_ID);
    }

    @Test
    void createTaskReturnsTaskIdAndDelegatesDtoToTaskManager() throws Exception {
        Task createdTask = taskWithStatus(TaskStatus.NEW);

        when(taskManager.createTask(any(TaskCreateRequestDto.class))).thenReturn(createdTask);

        mockMvc.perform(post("/status/api/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"smoke-create",
                                  "project":"demoApp",
                                  "countryCode":"us",
                                  "textContent":"hello",
                                  "userId":"agent",
                                  "targetList":["alpha","beta"],
                                  "batchSize":2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.message").value("任务创建成功"));

        verify(taskManager).createTask(argThat(dto ->
                "smoke-create".equals(dto.getTaskName())
                        && "demoApp".equals(dto.getProject())
                        && "us".equals(dto.getCountryCode())
                        && "hello".equals(dto.getTextContent())
                        && "agent".equals(dto.getUserId())
                        && dto.getBatchSize() == 2
                        && java.util.List.of("alpha", "beta").equals(dto.getTargetList())
        ));
    }

    @Test
    void getTaskReturnsTaskAndAggregatedTargetList() throws Exception {
        Task task = taskWithStatus(TaskStatus.READY);

        when(taskManager.getTask(TASK_ID)).thenReturn(task);
        when(taskManager.getTaskMessages(TASK_ID)).thenReturn(java.util.List.of(
                new TaskMsg("msg-1", TASK_ID, "alpha"),
                new TaskMsg("msg-2", TASK_ID, "beta")
        ));

        mockMvc.perform(get("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.task.tid").value(TASK_ID))
                .andExpect(jsonPath("$.task.status").value("READY"))
                .andExpect(jsonPath("$.targetList[0]").value("alpha"))
                .andExpect(jsonPath("$.targetList[1]").value("beta"));
    }

    @Test
    void getTaskReturnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(null);

        mockMvc.perform(get("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTaskReturnsSuccessWhenTaskExists() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.NEW));
        when(taskManager.deleteTask(TASK_ID)).thenReturn(true);

        mockMvc.perform(delete("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务删除成功"));

        verify(taskManager).deleteTask(TASK_ID);
    }

    @Test
    void deleteTaskReturnsBadRequestWhenDeleteRejected() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(taskWithStatus(TaskStatus.READY));
        when(taskManager.deleteTask(TASK_ID)).thenReturn(false);

        mockMvc.perform(delete("/status/api/tasks/{taskId}", TASK_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void updateTaskMutatesExistingTaskAndDelegatesToTaskManager() throws Exception {
        Task existingTask = taskWithStatus(TaskStatus.NEW);
        com.xa.mass.base.model.User user = new com.xa.mass.base.model.User();
        user.setName("before");
        existingTask.setUser(user);

        when(taskManager.getTask(TASK_ID)).thenReturn(existingTask);

        mockMvc.perform(put("/status/api/tasks/{taskId}", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"updated-name",
                                  "project":"telegramApp",
                                  "countryCode":"sg",
                                  "textContent":"updated-content",
                                  "userId":"updated-user",
                                  "targetList":["one","two"],
                                  "batchSize":5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务信息已更新"));

        verify(taskManager).updateTask(argThat(task ->
                TASK_ID.equals(task.getTid())
                        && "updated-name".equals(task.getTaskName())
                        && "telegramApp".equals(task.getProjectCode())
                        && "sg".equals(task.getTaskCountry())
                        && "updated-content".equals(task.getTextContent())
                        && task.getUser() != null
                        && "updated-user".equals(task.getUser().getName())
                        && task.getBatchSize() == 5
        ));
    }

    @Test
    void updateTaskReturnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(taskManager.getTask(TASK_ID)).thenReturn(null);

        mockMvc.perform(put("/status/api/tasks/{taskId}", TASK_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"missing-task"
                                }
                                """))
                .andExpect(status().isNotFound());

        verify(taskManager, never()).updateTask(any(Task.class));
    }

    @Test
    void getTaskMessagesReturnsPagedMessages() throws Exception {
        when(taskManager.getTaskMessages(TASK_ID)).thenReturn(java.util.List.of(
                new TaskMsg("msg-1", TASK_ID, "alpha"),
                new TaskMsg("msg-2", TASK_ID, "beta"),
                new TaskMsg("msg-3", TASK_ID, "gamma")
        ));

        mockMvc.perform(get("/status/api/tasks/{taskId}/messages", TASK_ID)
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].msgId").value("msg-3"))
                .andExpect(jsonPath("$.messages[0].target").value("gamma"));
    }

    private Task taskWithStatus(TaskStatus status) {
        Task task = new Task();
        task.setTid(TASK_ID);
        task.setStatus(status);
        return task;
    }
}
