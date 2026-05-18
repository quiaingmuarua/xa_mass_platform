package com.xa.mass.api.internal;

import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogControllerTest {

    private MockMvc mockMvc;
    private RuntimeDiagnosticsOperations runtimeDiagnostics;
    @BeforeEach
    void setUp() {
        ProjectEventCatalogRegistry catalog = DefaultProjectEventCatalogFactory.createDefaultProjectRegistry();
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(java.util.List.of(PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .projectCodes(java.util.List.of("crawlerApp", "demoApp", "demoApp"))
                .priorityClass(PriorityClass.BULK)
                .responseMode(ResponseMode.FINAL_RESULT)
                .targetScope(TargetScope.WORKER)
                .build());
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(java.util.List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(java.util.List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        catalog.registerEventDefinition(EventDefinition.builder()
                .code("sms.wait-code")
                .name("SMS Wait Code")
                .description("Example sms wait-code task event.")
                .payloadTypes(java.util.List.of(PayloadType.JSON))
                .taskModes(java.util.List.of())
                .priorityClass(PriorityClass.CONTROL)
                .responseMode(ResponseMode.ACK)
                .targetScope(TargetScope.TASK_ENGINE)
                .handler((request, principal) -> EventResponse.success(java.util.Map.of(), request.getRequestId()))
                .build());
        WorkerSnapshot crawlerWorker = worker("crawler-worker-1", "ONLINE",
                List.of("otherApp"), List.of("crawler.fetch-page"));
        WorkerSnapshot offlineChatWorker = worker("chat-worker-1", "OFFLINE",
                List.of("demoApp"), List.of("chatbot.reply"));
        WorkerSnapshot scopeOnlyWorker = worker("scope-only-worker", "ONLINE",
                List.of("demoApp"), List.of());
        WorkerQueryOperations workerQueries = mock(WorkerQueryOperations.class);
        when(workerQueries.getAllWorkers()).thenReturn(List.of(crawlerWorker, offlineChatWorker, scopeOnlyWorker));
        when(workerQueries.isWorkerOnline("crawler-worker-1")).thenReturn(true);
        when(workerQueries.isWorkerOnline("chat-worker-1")).thenReturn(false);
        when(workerQueries.isWorkerOnline("scope-only-worker")).thenReturn(false);
        runtimeDiagnostics = mock(RuntimeDiagnosticsOperations.class);
        when(runtimeDiagnostics.isWorkerLocked("crawler-worker-1")).thenReturn(false);
        when(runtimeDiagnostics.isWorkerLocked("chat-worker-1")).thenReturn(true);
        when(runtimeDiagnostics.isWorkerLocked("scope-only-worker")).thenReturn(false);
        when(runtimeDiagnostics.listSessions()).thenReturn(List.of(
                java.util.Map.of(
                        "workerId", "crawler-worker-1",
                        "connections", java.util.List.of(java.util.Map.of(
                                "active", true,
                                "endpointId", "ws-crawler-1",
                                "routeKey", "route-crawler-1",
                                "adapterId", "ws-public"
                        ))
                ),
                java.util.Map.of(
                        "workerId", "scope-only-worker",
                        "connections", java.util.List.of(java.util.Map.of(
                                "active", true,
                                "endpointId", "poll-1",
                                "routeKey", "scope-only-worker",
                                "adapterId", "polling"
                        ))
                )
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CatalogController(catalog, workerQueries, runtimeDiagnostics)
        ).build();
    }

    @Test
    void listEventsReturnsEventDefinitions() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='sms.wait-code')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.priorityClass=='BULK')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.responseMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.targetScope=='WORKER')]").exists());
    }

    @Test
    void eventCapabilitiesReturnInvocationModelAndLiveWorkerCoverage() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/event-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.invocationModel=='TASK_BACKED')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.priorityClass=='BULK')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.responseMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.targetScope=='WORKER')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.projectCodes[0]=='crawlerApp' && @.projectCodes[1]=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.workerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.onlineWorkerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.invocationModel=='DIRECT_RUNTIME')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.priorityClass=='CONTROL')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.responseMode=='ACK')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.targetScope=='TASK_ENGINE')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.hasDirectRuntimeHandler==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='chatbot.reply' && @.hasOnlineWorkerCoverage==false)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.workerIds.length()==1)]").exists());
    }

    @Test
    void workerCapabilitiesJoinCatalogWorkerAndTransportFacts() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/worker-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.supportedEventCodes[0]=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].eventCode=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].projectCodes[0]=='crawlerApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].projectCodes[1]=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.maxConcurrentWork==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.connections[0].endpointId=='ws-crawler-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.connections[0].routeKey=='route-crawler-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.connections[0].adapterId=='ws-public')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.hasActiveEndpoint==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='chat-worker-1' && @.locked==true)]").exists());
    }

    @Test
    void missingProjectOrEventReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/events/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private WorkerSnapshot worker(String workerId,
                                  String status,
                                  List<String> supportedProjects,
                                  List<String> supportedEventCodes) {
        String adapterId = null;
        String transportHint = null;
        if ("crawler-worker-1".equals(workerId)) {
            adapterId = "websocket";
            transportHint = "realtime";
        } else if ("scope-only-worker".equals(workerId)) {
            adapterId = "polling";
            transportHint = "polling";
        }
        return new WorkerSnapshot(
                workerId,
                status,
                null,
                null,
                supportedProjects,
                supportedEventCodes,
                List.of(),
                null,
                adapterId,
                transportHint,
                1,
                java.util.Map.of(),
                null,
                null
        );
    }
}
