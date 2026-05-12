package com.xa.mass.api.internal;

import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.WorkerContextSnapshot;
import com.xa.mass.sdk.model.WorkerSnapshot;
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
    private RuntimeDiagnosticsOperations runtimeDiagnostics;

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
        metadataCatalog.registerProject(ProjectDefinition.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Demo")
                .eventCodes(List.of("demo.dispatch"))
                .build());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerApiController(workerQueries, metadataCatalog, runtimeDiagnostics))
                .setControllerAdvice(new com.xa.mass.api.aop.GlobalExceptionHandler())
                .build();
    }

    @Test
    void listWorkersReturnsReadModel() throws Exception {
        WorkerSnapshot worker = new WorkerSnapshot(
                "worker-001",
                "ONLINE",
                "1.2.3",
                LocalDateTime.of(2026, 4, 21, 10, 15),
                List.of("demoApp"),
                List.of("demo.dispatch"),
                "group-a",
                "websocket",
                "realtime",
                Map.of("region", "us"),
                null,
                LocalDateTime.of(2026, 4, 21, 10, 16)
        );

        when(workerQueries.getAllWorkers()).thenReturn(List.of(worker));
        when(workerQueries.isWorkerOnline("worker-001")).thenReturn(true);
        when(workerQueries.isWorkerLocked("worker-001")).thenReturn(true);
        when(runtimeDiagnostics.listSessions()).thenReturn(List.of(Map.of(
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
                .andExpect(jsonPath("$.data.items[0].transportReachability").value("ONLINE"))
                .andExpect(jsonPath("$.data.items[0].transportOnline").value(true))
                .andExpect(jsonPath("$.data.items[0].connections[0].endpointId").value("ws-1"))
                .andExpect(jsonPath("$.data.items[0].connections[0].routeKey").value("route-1"))
                .andExpect(jsonPath("$.data.items[0].connections[0].adapterId").value("ws-public"))
                .andExpect(jsonPath("$.data.items[0].hasActiveEndpoint").value(true))
                .andExpect(jsonPath("$.data.items[0].locked").value(true))
                .andExpect(jsonPath("$.data.items[0].lastHeartbeat").value("2026-04-21 10:15:00"));
    }

    @Test
    void listWorkerContextsReturnsReadModel() throws Exception {
        WorkerContextSnapshot workerContext = new WorkerContextSnapshot(
                "ctx-001",
                "worker-001",
                "demoApp",
                "OCCUPIED",
                java.util.Set.of("telegram", "sms"),
                "task-123",
                null,
                null,
                LocalDateTime.of(2026, 4, 21, 9, 55),
                LocalDateTime.of(2026, 4, 21, 9, 50),
                Map.of("account", "acc-01")
        );

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
