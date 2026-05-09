package com.xa.mass.api.internal;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.internal.TransportDebugOperations;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerApiControllerTest {

    @Mock
    private WorkerQueryOperations workerQueries;

    @Mock
    private TransportDebugOperations transportDebugOperations;

    private MockMvc mockMvc;
    private ProjectEventCatalogRegistry metadataCatalog;

    @BeforeEach
    void setUp() {
        metadataCatalog = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
        metadataCatalog.registerEventDefinition(EventDefinition.builder()
                .code("demo.dispatch")
                .name("Demo Dispatch")
                .description("Dispatch demo work")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("demoApp"))
                .build());
        metadataCatalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Demo")
                .eventCodes(List.of("demo.dispatch"))
                .build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerApiController(workerQueries, metadataCatalog, transportDebugOperations))
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
        worker.setAdapterId("websocket");
        worker.setOnlineStrategy("realtime");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        worker.setAttributes(Map.of("region", "us"));
        worker.setLastHeartbeat(LocalDateTime.of(2026, 4, 21, 10, 15));
        worker.setUpdateTime(LocalDateTime.of(2026, 4, 21, 10, 16));

        when(workerQueries.getAllWorkers()).thenReturn(List.of(worker));
        when(workerQueries.isWorkerLocked("worker-001")).thenReturn(true);
        when(transportDebugOperations.listSessions()).thenReturn(List.of(Map.of(
                "workerId", "worker-001",
                "connections", List.of(Map.of(
                        "active", true,
                        "endpointId", "ws-1",
                        "routeKey", "route-1",
                        "adapterId", "ws-public"
                ))
        )));

        mockMvc.perform(get("/api/v1/runtime/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.items[0].supportedEventCodes[0]").value("demo.dispatch"))
                .andExpect(jsonPath("$.data.items[0].eventBindings[0].eventCode").value("demo.dispatch"))
                .andExpect(jsonPath("$.data.items[0].eventBindings[0].projectCodes[0]").value("demoApp"))
                .andExpect(jsonPath("$.data.items[0].adapterId").value("websocket"))
                .andExpect(jsonPath("$.data.items[0].transportHint").value("realtime"))
                .andExpect(jsonPath("$.data.items[0].connections[0].endpointId").value("ws-1"))
                .andExpect(jsonPath("$.data.items[0].connections[0].routeKey").value("route-1"))
                .andExpect(jsonPath("$.data.items[0].connections[0].adapterId").value("ws-public"))
                .andExpect(jsonPath("$.data.items[0].hasActiveEndpoint").value(true))
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

        when(workerQueries.getAllWorkerContexts()).thenReturn(List.of(workerContext));

        mockMvc.perform(get("/api/v1/runtime/worker-contexts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].workerContextId").value("ctx-001"))
                .andExpect(jsonPath("$.data.items[0].project").value("demoApp"))
                .andExpect(jsonPath("$.data.items[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.data.items[0].lastBindTaskId").value("task-123"));
    }
}
