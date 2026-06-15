package com.xa.mass.api.internal;

import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerStateProjectionSnapshot;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerApiControllerTest {

    @Mock
    private WorkerQueryOperations workerQueries;

    @Mock
    private RuntimeDiagnosticsOperations runtimeDiagnostics;

    @Mock
    private WorkerControlOperations workerControl;

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
                .standaloneSetup(new WorkerApiController(workerQueries, metadataCatalog, runtimeDiagnostics, workerControl))
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
                List.of(),
                "group-a",
                "websocket",
                "realtime",
                3,
                Map.of("region", "us"),
                null,
                LocalDateTime.of(2026, 4, 21, 10, 16)
        );

        when(workerQueries.getAllWorkers()).thenReturn(List.of(worker));
        when(workerQueries.listReachableWorkerIds()).thenReturn(List.of("worker-001"));
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(List.of("worker-001"));
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
                .andExpect(jsonPath("$.data.items[0].maxConcurrentWork").value(3))
                .andExpect(jsonPath("$.data.items[0].eventBindings[0].eventCode").value("demo.dispatch"))
                .andExpect(jsonPath("$.data.items[0].eventBindings[0].projectCodes[0]").value("demoApp"))
                .andExpect(jsonPath("$.data.items[0].adapterId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].transportHint").value("realtime"))
                .andExpect(jsonPath("$.data.items[0].runtimeStatus").value("ONLINE"))
                .andExpect(jsonPath("$.data.items[0].reachability").value("ONLINE"))
                .andExpect(jsonPath("$.data.items[0].reachable").value(true))
                .andExpect(jsonPath("$.data.items[0].connections[0].endpointId").value("ws-1"))
                .andExpect(jsonPath("$.data.items[0].connections[0].routeKey").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].connections[0].adapterId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].hasActiveEndpoint").value(true))
                .andExpect(jsonPath("$.data.items[0].locked").value(true))
                .andExpect(jsonPath("$.data.items[0].lastHeartbeat").value("2026-04-21 10:15:00"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.workerGroupId").value("declaration"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.runtimeStatus").value("runtimeStatusDisplay"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.reachability").value("workerRuntimeReachability"))
                .andExpect(jsonPath("$.data.items[0].fieldSources.supportedEventCodes")
                        .value("compatibilityProjection"));
    }

    @Test
    void listWorkersAppliesDiagnosticResponseLimit() throws Exception {
        WorkerSnapshot worker1 = workerSnapshot("worker-001");
        WorkerSnapshot worker2 = workerSnapshot("worker-002");
        when(workerQueries.getAllWorkers()).thenReturn(List.of(worker1, worker2));
        when(workerQueries.listReachableWorkerIds()).thenReturn(List.of("worker-001", "worker-002"));
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(List.of());
        when(runtimeDiagnostics.listSessions()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/runtime/workers").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.limit").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-001"));
        verify(workerQueries, times(1)).listReachableWorkerIds();
        verify(runtimeDiagnostics, times(1)).listLockedWorkerIds();
    }

    @Test
    void listWorkersReadsRuntimeFactsFromBoundedSnapshotsWithLargeFixture() throws Exception {
        List<WorkerSnapshot> workers = largeWorkerFixture(120, 5);
        when(workerQueries.getAllWorkers()).thenReturn(workers);
        when(workerQueries.listReachableWorkerIds()).thenReturn(reachableWorkerIds(workers, 2));
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(List.of(
                "worker-0003",
                "worker-0042",
                "worker-0099"
        ));
        when(runtimeDiagnostics.listSessions()).thenReturn(sessionFacts(workers, 20));

        mockMvc.perform(get("/api/v1/runtime/workers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(120))
                .andExpect(jsonPath("$.data.limit").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(120))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-0001"))
                .andExpect(jsonPath("$.data.items[0].reachable").value(false))
                .andExpect(jsonPath("$.data.items[1].workerId").value("worker-0002"))
                .andExpect(jsonPath("$.data.items[1].reachable").value(true))
                .andExpect(jsonPath("$.data.items[2].locked").value(true))
                .andExpect(jsonPath("$.data.items[19].connections[0].endpointId").value("endpoint-worker-0020"));

        verify(workerQueries, times(1)).getAllWorkers();
        verify(workerQueries, times(1)).listReachableWorkerIds();
        verify(runtimeDiagnostics, times(1)).listLockedWorkerIds();
        verify(runtimeDiagnostics, times(1)).listSessions();
    }

    @Test
    void workerStateEndpointsDelegateToSdkWorkerControl() throws Exception {
        Instant observedAt = Instant.parse("2026-05-18T10:00:00Z");
        WorkerStateProjectionSnapshot projection = new WorkerStateProjectionSnapshot(
                "worker-001", 3, "DRAINING", "maintenance", observedAt, observedAt
        );
        when(workerControl.getWorkerStateProjection("worker-001")).thenReturn(projection);
        when(workerControl.listWorkerStateProjections()).thenReturn(List.of(projection));

        mockMvc.perform(get("/api/v1/runtime/workers/{workerId}/state", "worker-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DRAINING"));

        mockMvc.perform(get("/api/v1/runtime/workers/states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.limit").value(200));
    }

    @Test
    void listWorkerStatesAppliesDiagnosticResponseLimit() throws Exception {
        Instant observedAt = Instant.parse("2026-05-18T10:00:00Z");
        WorkerStateProjectionSnapshot projection1 = new WorkerStateProjectionSnapshot(
                "worker-001", 3, "DRAINING", "maintenance", observedAt, observedAt
        );
        WorkerStateProjectionSnapshot projection2 = new WorkerStateProjectionSnapshot(
                "worker-002", 1, "ACTIVE", null, observedAt, observedAt
        );
        when(workerControl.listWorkerStateProjections()).thenReturn(List.of(projection1, projection2));

        mockMvc.perform(get("/api/v1/runtime/workers/states").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.limit").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].workerId").value("worker-001"));
    }

    @Test
    void workerCommandEndpointsDelegateToSdkWorkerControl() throws Exception {
        Instant now = Instant.parse("2026-05-18T10:05:00Z");
        WorkerCommandSnapshot command = new WorkerCommandSnapshot(
                "cmd-001",
                "worker-001",
                "DRAIN",
                "REQUESTED",
                "operator",
                "maintenance",
                "idem-1",
                1770000000000L,
                Map.of("mode", "soft"),
                null,
                0,
                null,
                now,
                now
        );
        when(workerControl.requestWorkerCommand(any())).thenReturn(new WorkerCommandResultSnapshot(
                "ACCEPTED", true, null, "REQUESTED", "created", command
        ));
        when(workerControl.getWorkerCommand("cmd-001")).thenReturn(command);
        when(workerControl.listWorkerCommandsForWorker("worker-001")).thenReturn(List.of(command));

        mockMvc.perform(post("/api/v1/runtime/workers/{workerId}/commands", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "commandId":"cmd-001",
                                  "commandType":"DRAIN",
                                  "requester":"operator",
                                  "reason":"maintenance",
                                  "idempotencyKey":"idem-1",
                                  "deadlineEpochMillis":1770000000000,
                                  "payload":{"mode":"soft"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.command.commandId").value("cmd-001"));

        ArgumentCaptor<WorkerCommandSubmitRequest> submitCaptor =
                ArgumentCaptor.forClass(WorkerCommandSubmitRequest.class);
        verify(workerControl).requestWorkerCommand(submitCaptor.capture());
        assertEquals("worker-001", submitCaptor.getValue().workerId());
        assertEquals("DRAIN", submitCaptor.getValue().commandType());

        mockMvc.perform(get("/api/v1/runtime/workers/commands/{commandId}", "cmd-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value("cmd-001"));

        mockMvc.perform(get("/api/v1/runtime/workers/{workerId}/commands", "worker-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.limit").value(200))
                .andExpect(jsonPath("$.data.items[0].commandId").value("cmd-001"));
    }

    @Test
    void listWorkerCommandsAppliesDiagnosticResponseLimit() throws Exception {
        Instant now = Instant.parse("2026-05-18T10:05:00Z");
        WorkerCommandSnapshot command1 = workerCommand("cmd-001", now);
        WorkerCommandSnapshot command2 = workerCommand("cmd-002", now);
        when(workerControl.listWorkerCommandsForWorker("worker-001")).thenReturn(List.of(command1, command2));

        mockMvc.perform(get("/api/v1/runtime/workers/{workerId}/commands", "worker-001")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.limit").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].commandId").value("cmd-001"));
    }

    private WorkerSnapshot workerSnapshot(String workerId) {
        return new WorkerSnapshot(
                workerId,
                "ONLINE",
                "1.2.3",
                LocalDateTime.of(2026, 4, 21, 10, 15),
                List.of("demoApp"),
                List.of("demo.dispatch"),
                List.of(),
                "group-a",
                "websocket",
                "realtime",
                3,
                Map.of("region", "us"),
                null,
                LocalDateTime.of(2026, 4, 21, 10, 16)
        );
    }

    private List<WorkerSnapshot> largeWorkerFixture(int workerCount, int groupCount) {
        List<WorkerSnapshot> workers = new ArrayList<>();
        for (int index = 1; index <= workerCount; index++) {
            String workerId = "worker-%04d".formatted(index);
            String groupId = "group-%02d".formatted((index - 1) % groupCount);
            workers.add(new WorkerSnapshot(
                    workerId,
                    index % 3 == 0 ? "OFFLINE" : "ONLINE",
                    "1.2.%d".formatted(index % 10),
                    LocalDateTime.of(2026, 4, 21, 10, index % 60),
                    List.of("demoApp"),
                    List.of("demo.dispatch"),
                    List.of(),
                    groupId,
                    "node-%02d".formatted((index - 1) % groupCount),
                    "polling",
                    "polling",
                    3,
                    Map.of(
                            "region", index % 2 == 0 ? "us" : "sg",
                            "fixture", "bounded-fanout"
                    ),
                    null,
                    LocalDateTime.of(2026, 4, 21, 11, index % 60)
            ));
        }
        return workers;
    }

    private List<String> reachableWorkerIds(List<WorkerSnapshot> workers, int everyNthWorker) {
        return workers.stream()
                .filter(worker -> numericSuffix(worker.getWorkerId()) % everyNthWorker == 0)
                .map(WorkerSnapshot::getWorkerId)
                .toList();
    }

    private List<Map<String, Object>> sessionFacts(List<WorkerSnapshot> workers, int everyNthWorker) {
        return workers.stream()
                .filter(worker -> numericSuffix(worker.getWorkerId()) % everyNthWorker == 0)
                .map(worker -> Map.<String, Object>of(
                        "workerId", worker.getWorkerId(),
                        "connections", List.of(Map.of(
                                "active", true,
                                "endpointId", "endpoint-" + worker.getWorkerId(),
                                "routeKey", "route-" + worker.getWorkerId(),
                                "adapterId", "polling"
                        ))
                ))
                .toList();
    }

    private int numericSuffix(String workerId) {
        return Integer.parseInt(workerId.substring(workerId.lastIndexOf('-') + 1));
    }

    private WorkerCommandSnapshot workerCommand(String commandId, Instant now) {
        return new WorkerCommandSnapshot(
                commandId,
                "worker-001",
                "DRAIN",
                "REQUESTED",
                "operator",
                "maintenance",
                commandId,
                1770000000000L,
                Map.of("mode", "soft"),
                null,
                0,
                null,
                now,
                now
        );
    }
}
