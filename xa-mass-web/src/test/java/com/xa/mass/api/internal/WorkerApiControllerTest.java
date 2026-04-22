package com.xa.mass.api.internal;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.storage.TaskStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerApiControllerTest {

    private WorkerManager workerManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerManager = new WorkerManager(TaskStorageFactory.createDefaultWorkerStorage());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerApiController(workerManager))
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
        workerManager.addWorker(worker);
        workerManager.tryLockWorker("worker-001");

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
        workerContext.setChannel("telegram");
        workerContext.setAttributes(Map.of("account", "acc-01"));
        workerContext.setLastBindTaskId("task-123");
        workerContext.setLastUsedTime(LocalDateTime.of(2026, 4, 21, 9, 50));
        workerContext.setUpdateTime(LocalDateTime.of(2026, 4, 21, 9, 55));
        workerManager.addWorkerContext(workerContext);

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
        workerManager.addWorker(worker);

        mockMvc.perform(put("/status/api/workers/{workerId}/supported-projects", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "supportedProjects": ["demoApp", "telegramApp"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.supportedProjects.length()").value(2));

        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("demoApp", "telegramApp"),
                workerManager.getWorker("worker-001").getSupportedProjects()
        );
    }

    @Test
    void updateSupportedProjectsRejectsUnknownFields() throws Exception {
        Worker worker = new Worker();
        worker.setWorkerId("worker-001");
        workerManager.addWorker(worker);

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

    @Test
    void updateSupportedProjectsRequiresSupportedProjectsField() throws Exception {
        Worker worker = new Worker();
        worker.setWorkerId("worker-001");
        workerManager.addWorker(worker);

        mockMvc.perform(put("/status/api/workers/{workerId}/supported-projects", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("supportedProjects is required"));
    }
}
