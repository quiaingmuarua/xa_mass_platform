package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskSummarySnapshot;
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
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskApiController(
                taskQueries,
                null,
                taskAdmin,
                DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(),
                ApiAuthTestSupport.defaultOperatorAuthService(),
                new ApiAuthorizationService(),
                new TaskSecurityViewSupport(),
                null,
                null,
                null
        )).build();
    }

    @Test
    void listTasksFiltersByKeywordAndStatus() throws Exception {
        TaskExecutionOptions runningExecution = new TaskExecutionOptions();
        runningExecution.setBatchSize(2);
        TaskSummarySnapshot runningTask = new TaskSummarySnapshot(
                "task-001",
                "Warm worker pool",
                "default",
                "demoApp",
                "agent",
                null,
                "RUNNING",
                null,
                runningExecution,
                6,
                10,
                LocalDateTime.of(2026, 4, 21, 9, 30)
        );

        TaskExecutionOptions pausedExecution = new TaskExecutionOptions();
        pausedExecution.setBatchSize(1);
        TaskSummarySnapshot pausedTask = new TaskSummarySnapshot(
                "task-002",
                "Review backlog",
                "default",
                "demoApp",
                "agent",
                null,
                "PAUSED",
                null,
                pausedExecution,
                2,
                8,
                LocalDateTime.of(2026, 4, 21, 8, 0)
        );

        when(taskQueries.getTaskSummariesByStatus("RUNNING")).thenReturn(List.of(runningTask));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("keyword", "warm")
                        .param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value("task-001"))
                .andExpect(jsonPath("$.data.items[0].id").value("task-001"))
                .andExpect(jsonPath("$.data.items[0].taskName").value("Warm worker pool"))
                .andExpect(jsonPath("$.data.items[0].execution.batchSize").value(2))
                .andExpect(jsonPath("$.data.items[0].execution.foreground").value(true))
                .andExpect(jsonPath("$.data.items[0].counters.successCount").value(6))
                .andExpect(jsonPath("$.data.items[0].timestamps.updatedAt").value("2026-04-21 09:30:00"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.taskId").value("controlPlaneShell"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.status").value("runtimeCurrent"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.successCount").value("compatibilityAlias"))
                .andExpect(jsonPath("$.data.items[0].successCount").value(6))
                .andExpect(jsonPath("$.data.items[0].eligibleCount").value(10))
                .andExpect(jsonPath("$.data.items[0].updatedAt").value("2026-04-21 09:30:00"));
    }

    @Test
    void listTasksFiltersByProject() throws Exception {
        TaskSummarySnapshot demoTask = new TaskSummarySnapshot(
                "task-001",
                "Warm worker pool",
                "default",
                "demoApp",
                "agent",
                null,
                "RUNNING",
                null,
                new TaskExecutionOptions(),
                6,
                10,
                LocalDateTime.of(2026, 4, 21, 9, 30)
        );
        TaskSummarySnapshot opsTask = new TaskSummarySnapshot(
                "task-002",
                "Audit queue",
                "default",
                "demoOps",
                "ops-agent",
                null,
                "PAUSED",
                null,
                new TaskExecutionOptions(),
                2,
                8,
                LocalDateTime.of(2026, 4, 21, 8, 0)
        );

        when(taskQueries.listTaskSummaries(0, 500)).thenReturn(List.of(demoTask, opsTask));

        mockMvc.perform(get("/api/v1/tasks")
                        .param("project", "demoOps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("task-002"))
                .andExpect(jsonPath("$.data.items[0].project").value("demoOps"));
    }
}
