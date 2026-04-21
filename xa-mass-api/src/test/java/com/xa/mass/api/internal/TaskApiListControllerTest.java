package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskApiListControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TaskApiController controller = new TaskApiController();
        ReflectionTestUtils.setField(controller, "taskManager", new StaticTaskManager());
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listTasksFiltersByKeywordAndStatus() throws Exception {
        mockMvc.perform(get("/status/api/tasks")
                        .param("keyword", "warm")
                        .param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("task-001"))
                .andExpect(jsonPath("$.items[0].taskName").value("Warm worker pool"))
                .andExpect(jsonPath("$.items[0].routingCode").value("us"))
                .andExpect(jsonPath("$.items[0].successCount").value(6))
                .andExpect(jsonPath("$.items[0].eligibleCount").value(10))
                .andExpect(jsonPath("$.items[0].updatedAt").value("2026-04-21 09:30:00"));
    }

    static class StaticTaskManager extends TaskManager {
        StaticTaskManager() {
            super(new NoopTaskScheduler(), TaskStorageFactory.createDefaultTaskStorage());
        }

        @Override
        public List<Task> getAllTasks() {
            Task runningTask = new Task();
            runningTask.setTid("task-001");
            runningTask.setStatus(TaskStatus.RUNNING);
            runningTask.setTaskName("Warm worker pool");
            runningTask.setProject("demoApp");
            runningTask.setTaskRoutingCode("us");
            runningTask.setTaskEligibleNumber(10);
            runningTask.setTaskSuccessNumber(6);
            runningTask.setBatchSize(2);
            runningTask.setUpdateTime(LocalDateTime.of(2026, 4, 21, 9, 30));

            Task pausedTask = new Task();
            pausedTask.setTid("task-002");
            pausedTask.setStatus(TaskStatus.PAUSED);
            pausedTask.setTaskName("Review backlog");
            pausedTask.setProject("demoApp");
            pausedTask.setTaskRoutingCode("sg");
            pausedTask.setTaskEligibleNumber(8);
            pausedTask.setTaskSuccessNumber(2);
            pausedTask.setBatchSize(1);
            pausedTask.setUpdateTime(LocalDateTime.of(2026, 4, 21, 8, 0));
            return List.of(runningTask, pausedTask);
        }
    }

    static class NoopTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean handleTaskMsgCompletion(com.xa.mass.base.model.TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean handleTaskMsgFailure(com.xa.mass.base.model.TaskMsg taskMsg, String errorMessage) {
            return true;
        }

        @Override
        public boolean retryTaskMsg(com.xa.mass.base.model.TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }
}
