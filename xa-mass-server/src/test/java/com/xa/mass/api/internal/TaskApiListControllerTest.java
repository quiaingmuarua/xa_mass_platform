package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TaskApiListControllerTest {

    @Mock
    private TaskQueryOperations taskQueries;

    @Mock
    private TaskAdminOperations taskAdmin;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskApiController(taskQueries, taskAdmin)).build();
    }

    @Test
    void listTasksFiltersByKeywordAndStatus() throws Exception {
        Task runningTask = new Task();
        runningTask.setTid("task-001");
        runningTask.setStatus(TaskStatus.RUNNING);
        runningTask.setTaskName("Warm worker pool");
        runningTask.setProject("demoApp");
        runningTask.setTaskEligibleNumber(10);
        runningTask.setTaskSuccessNumber(6);
        runningTask.setBatchSize(2);
        runningTask.setUpdateTime(LocalDateTime.of(2026, 4, 21, 9, 30));

        Task pausedTask = new Task();
        pausedTask.setTid("task-002");
        pausedTask.setStatus(TaskStatus.PAUSED);
        pausedTask.setTaskName("Review backlog");
        pausedTask.setProject("demoApp");
        pausedTask.setTaskEligibleNumber(8);
        pausedTask.setTaskSuccessNumber(2);
        pausedTask.setBatchSize(1);
        pausedTask.setUpdateTime(LocalDateTime.of(2026, 4, 21, 8, 0));

        when(taskQueries.getTasksByStatus(TaskStatus.RUNNING)).thenReturn(List.of(runningTask));

        mockMvc.perform(get("/status/api/tasks")
                        .param("keyword", "warm")
                        .param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("task-001"))
                .andExpect(jsonPath("$.data.items[0].taskName").value("Warm worker pool"))
                .andExpect(jsonPath("$.data.items[0].successCount").value(6))
                .andExpect(jsonPath("$.data.items[0].eligibleCount").value(10))
                .andExpect(jsonPath("$.data.items[0].updatedAt").value("2026-04-21 09:30:00"));
    }
}
