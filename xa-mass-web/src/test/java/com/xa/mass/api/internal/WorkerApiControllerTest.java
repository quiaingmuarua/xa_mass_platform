package com.xa.mass.api.internal;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.sdk.WorkerOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerApiControllerTest {

    @Mock
    private WorkerOperations workerOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerApiController(workerOperations))
                .setControllerAdvice(new com.xa.mass.api.aop.GlobalExceptionHandler())
                .build();
    }

    @Test
    void listWorkersReturnsReadModel() throws Exception {
        Worker worker = new Worker();
        worker.setWorkerId("worker-001");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("group-a");
        worker.setAgentVersion("1.2.3");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("region", "us"));
        worker.setLastHeartbeat(LocalDateTime.of(2026, 4, 21, 10, 15));
        worker.setUpdateTime(LocalDateTime.of(2026, 4, 21, 10, 16));

        when(workerOperations.getAllWorkers()).thenReturn(List.of(worker));
        when(workerOperations.isWorkerLocked("worker-001")).thenReturn(true);

        mockMvc.perform(get("/status/api/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.items[0].locked").value(true))
                .andExpect(jsonPath("$.data.items[0].lastHeartbeat").value("2026-04-21 10:15:00"));
    }

    @Test
    void listWorkerContextsReturnsReadModel() throws Exception {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-001");
        workerContext.setWorkerId("worker-001");
        workerContext.setProject("demoApp");
        workerContext.setStatus(WorkerContextStatus.OCCUPIED);
        workerContext.setRoutingTags(java.util.Set.of("telegram", "sms"));
        workerContext.setAttributes(Map.of("account", "acc-01"));
        workerContext.setLastBindTaskId("task-123");
        workerContext.setLastUsedTime(LocalDateTime.of(2026, 4, 21, 9, 50));
        workerContext.setUpdateTime(LocalDateTime.of(2026, 4, 21, 9, 55));

        when(workerOperations.getAllWorkerContexts()).thenReturn(List.of(workerContext));

        mockMvc.perform(get("/status/api/worker-contexts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workerContextId").value("ctx-001"))
                .andExpect(jsonPath("$.data.items[0].project").value("demoApp"))
                .andExpect(jsonPath("$.data.items[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.data.items[0].lastBindTaskId").value("task-123"));
    }

    @Test
    void updateSupportedProjectsMutatesWorker() throws Exception {
        Worker worker = new Worker();
        worker.setWorkerId("worker-001");
        when(workerOperations.getWorker("worker-001")).thenReturn(worker);

        mockMvc.perform(put("/status/api/workers/{workerId}/supported-projects", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "supportedProjects": ["demoApp", "testApp"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.supportedProjects.length()").value(2));

        verify(workerOperations).updateWorker(argThat(updated ->
                "worker-001".equals(updated.getWorkerId())
                        && List.of("demoApp", "testApp").equals(updated.getSupportedProjects())
        ));
    }

    @Test
    void updateSupportedProjectsRejectsUnknownFields() throws Exception {
        mockMvc.perform(put("/status/api/workers/{workerId}/supported-projects", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "supportedProjects": ["demoApp"],
                                  "workerGroupId": "legacy"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("Unsupported worker update fields: workerGroupId"));
    }
}
