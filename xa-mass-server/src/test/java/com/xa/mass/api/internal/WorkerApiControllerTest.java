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
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerStateProjectionSnapshot;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
        when(workerQueries.isWorkerOnline("worker-001")).thenReturn(true);
        when(runtimeDiagnostics.isWorkerLocked("worker-001")).thenReturn(true);
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
    void reportWorkerCapabilityDelegatesToSdkWorkerControl() throws Exception {
        when(workerControl.reportWorkerCapability(any())).thenReturn(new WorkerCapabilityReportSnapshot(
                "ACCEPTED", "worker-001", 7, true, true, "updated"
        ));

        mockMvc.perform(post("/api/v1/runtime/workers/{workerId}/capability-reports", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "capabilityVersion":7,
                                  "availableEventCodes":["demo.dispatch"],
                                  "schedulingAttributes":{"country":"us"},
                                  "agentVersion":"1.2.3"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("worker-001"))
                .andExpect(jsonPath("$.data.accepted").value(true));

        ArgumentCaptor<WorkerCapabilityReportRequest> captor =
                ArgumentCaptor.forClass(WorkerCapabilityReportRequest.class);
        verify(workerControl).reportWorkerCapability(captor.capture());
        assertEquals("worker-001", captor.getValue().workerId());
        assertEquals(List.of("demo.dispatch"), captor.getValue().availableEventCodes());
        assertEquals("us", captor.getValue().schedulingAttributes().get("country"));
    }

    @Test
    void workerStateEndpointsDelegateToSdkWorkerControl() throws Exception {
        Instant observedAt = Instant.parse("2026-05-18T10:00:00Z");
        WorkerStateProjectionSnapshot projection = new WorkerStateProjectionSnapshot(
                "worker-001", 3, "DRAINING", "maintenance", observedAt, observedAt
        );
        when(workerControl.reportWorkerState(any())).thenReturn(new WorkerStateReportSnapshot(
                "ACCEPTED", "worker-001", 3, true, true, "updated", projection
        ));
        when(workerControl.getWorkerStateProjection("worker-001")).thenReturn(projection);
        when(workerControl.listWorkerStateProjections()).thenReturn(List.of(projection));

        mockMvc.perform(post("/api/v1/runtime/workers/{workerId}/state-reports", "worker-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "stateVersion":3,
                                  "state":"DRAINING",
                                  "reason":"maintenance",
                                  "observedAt":"2026-05-18T10:00:00Z",
                                  "attributes":{"source":"operator"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projection.state").value("DRAINING"));

        ArgumentCaptor<WorkerStateReportRequest> captor =
                ArgumentCaptor.forClass(WorkerStateReportRequest.class);
        verify(workerControl).reportWorkerState(captor.capture());
        assertEquals("worker-001", captor.getValue().workerId());
        assertEquals("operator", captor.getValue().attributes().get("source"));

        mockMvc.perform(get("/api/v1/runtime/workers/{workerId}/state", "worker-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DRAINING"));

        mockMvc.perform(get("/api/v1/runtime/workers/states"))
                .andExpect(status().isOk())
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
        when(workerControl.acknowledgeWorkerCommand(any())).thenReturn(new WorkerCommandResultSnapshot(
                "ACCEPTED", true, "REQUESTED", "ACKED", "acked", command
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

        mockMvc.perform(post("/api/v1/runtime/workers/{workerId}/commands/{commandId}/ack", "worker-001", "cmd-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "status":"ACKED",
                                  "reason":"accepted"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStatus").value("ACKED"));

        verify(workerControl).acknowledgeWorkerCommand(eq(new WorkerCommandAcknowledgementRequest(
                "cmd-001", "ACKED", "accepted"
        )));

        mockMvc.perform(get("/api/v1/runtime/workers/commands/{commandId}", "cmd-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value("cmd-001"));

        mockMvc.perform(get("/api/v1/runtime/workers/{workerId}/commands", "worker-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].commandId").value("cmd-001"));
    }
}
