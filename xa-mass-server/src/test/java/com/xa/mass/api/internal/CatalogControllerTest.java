package com.xa.mass.api.internal;

import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerTopologyOperations;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.AdapterNodeSnapshot;
import com.xa.mass.sdk.model.NodeGroupBindingSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupSnapshot;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogControllerTest {

    private MockMvc mockMvc;
    private RuntimeDiagnosticsOperations runtimeDiagnostics;
    private WorkerQueryOperations workerQueries;
    private WorkerTopologyOperations workerTopology;
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
                List.of("legacyWorkerProject"), List.of("legacy.worker.event"));
        WorkerSnapshot offlineChatWorker = worker("chat-worker-1", "OFFLINE",
                List.of("demoApp"), List.of("chatbot.reply"));
        WorkerSnapshot scopeOnlyWorker = worker("scope-only-worker", "ONLINE",
                List.of("demoApp"), List.of());
        workerQueries = mock(WorkerQueryOperations.class);
        when(workerQueries.getAllWorkers()).thenReturn(List.of(crawlerWorker, offlineChatWorker, scopeOnlyWorker));
        when(workerQueries.listReachableWorkerIds()).thenReturn(List.of("crawler-worker-1"));
        runtimeDiagnostics = mock(RuntimeDiagnosticsOperations.class);
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(List.of("chat-worker-1"));
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
        workerTopology = mock(WorkerTopologyOperations.class);
        when(workerTopology.listWorkerGroups()).thenReturn(List.of(
                new WorkerGroupSnapshot(
                        "crawler",
                        List.of(WorkerEventBinding.builder()
                                .eventCode("crawler.fetch-page")
                                .projectCodes(List.of("crawlerApp", "demoApp"))
                                .build()),
                        List.of("crawlerApp", "demoApp"),
                        java.util.Map.of("category", "crawler"),
                        2),
                new WorkerGroupSnapshot(
                        "chat",
                        List.of(WorkerEventBinding.builder()
                                .eventCode("chatbot.reply")
                                .projectCodes(List.of("demoApp"))
                                .build()),
                        List.of("demoApp"),
                        java.util.Map.of("category", "chat"),
                        1)
        ));
        when(workerTopology.listAdapterNodes()).thenReturn(List.of(
                new AdapterNodeSnapshot("node-crawler", "websocket", "1", "ws-crawler", true, true,
                        null, null, java.util.Map.of()),
                new AdapterNodeSnapshot("node-chat", "polling", "1", "poll-chat", true, true,
                        null, null, java.util.Map.of())
        ));
        when(workerTopology.listNodeGroupBindings()).thenReturn(List.of(
                new NodeGroupBindingSnapshot("node-crawler", "crawler", "1", "test",
                        true, false, null, null, java.util.Map.of()),
                new NodeGroupBindingSnapshot("node-chat", "chat", "1", "test",
                        true, true, null, null, java.util.Map.of())
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CatalogController(catalog, workerQueries, workerTopology, runtimeDiagnostics)
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
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.deliveryAcknowledgementMode=='NONE')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page' && @.convergenceMode=='FINAL_RESULT')]").exists())
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
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.deliveryAcknowledgementMode=='NONE')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.convergenceMode=='FINAL_RESULT')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.targetScope=='WORKER')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.projectCodes[0]=='crawlerApp' && @.projectCodes[1]=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.declaredWorkerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.reachableWorkerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.invocationModel=='DIRECT_RUNTIME')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.priorityClass=='CONTROL')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.responseMode=='ACK')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.deliveryAcknowledgementMode=='HANDLER_ACCEPTED')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.convergenceMode=='NONE')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.targetScope=='TASK_ENGINE')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.hasDirectRuntimeHandler==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='chatbot.reply' && @.hasReachableWorkerCoverage==false)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.declaredWorkerIds.length()==1)]").exists());
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
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.fieldSources.workerGroupId=='declaration')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.reachability=='ONLINE')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.reachable==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.fieldSources.reachable=='workerRuntimeReachability')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.fieldSources.supportedEventCodes=='workerGroupCapability')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.supportedEventCodes[0]=='legacy.worker.event')]").doesNotExist())
                .andExpect(jsonPath("$.data[?(@.workerId=='chat-worker-1' && @.locked==true)]").exists());
    }

    @Test
    void workerGroupCapabilitiesExposeTopologyAndDispatchEligibility() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/worker-group-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.workerCount==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.projectCodes[0]=='crawlerApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.eventBindings[0].eventCode=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.adapterNodes[0].adapterNodeId=='node-crawler')]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.transportCounts.realtime==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.reachableWorkerCountsByTransport.realtime==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.runtimeStatusCounts.ONLINE==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.lockedCount==0)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='crawler' && @.reachableUnlockedBindingCount==1)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='chat' && @.reachableUnlockedBindingCount==0)]").exists());
    }

    @Test
    void workerGroupCapabilitiesReadsRuntimeFactsFromOnePresenceSnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/worker-group-capabilities"))
                .andExpect(status().isOk());

        verify(workerQueries, times(1)).listReachableWorkerIds();
        verify(runtimeDiagnostics, times(1)).listLockedWorkerIds();
    }

    @Test
    void workerCapabilitiesReadsRuntimeFactsFromBoundedSnapshotsWithLargeFixture() throws Exception {
        LargeWorkerFixture fixture = largeWorkerFixture(125, 5);
        reset(workerQueries, workerTopology, runtimeDiagnostics);
        when(workerQueries.getAllWorkers()).thenReturn(fixture.workers());
        when(workerQueries.listReachableWorkerIds()).thenReturn(fixture.reachableWorkerIds());
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(fixture.lockedWorkerIds());
        when(runtimeDiagnostics.listSessions()).thenReturn(fixture.sessions());
        when(workerTopology.listWorkerGroups()).thenReturn(fixture.groups());

        mockMvc.perform(get("/api/v1/catalog/worker-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(125))
                .andExpect(jsonPath("$.data[?(@.workerId=='worker-0002' && @.reachable==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='worker-0003' && @.locked==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='worker-0005' && @.connections[0].endpointId=='endpoint-worker-0005')]").exists());

        verify(workerQueries, times(1)).getAllWorkers();
        verify(workerQueries, times(1)).listReachableWorkerIds();
        verify(runtimeDiagnostics, times(1)).listLockedWorkerIds();
        verify(runtimeDiagnostics, times(1)).listSessions();
        verify(workerTopology, times(1)).listWorkerGroups();
    }

    @Test
    void workerGroupCapabilitiesReadsRuntimeFactsFromBoundedSnapshotsWithLargeFixture() throws Exception {
        LargeWorkerFixture fixture = largeWorkerFixture(125, 5);
        reset(workerQueries, workerTopology, runtimeDiagnostics);
        when(workerQueries.getAllWorkers()).thenReturn(fixture.workers());
        when(workerQueries.listReachableWorkerIds()).thenReturn(fixture.reachableWorkerIds());
        when(runtimeDiagnostics.listLockedWorkerIds()).thenReturn(fixture.lockedWorkerIds());
        when(workerTopology.listWorkerGroups()).thenReturn(fixture.groups());
        when(workerTopology.listAdapterNodes()).thenReturn(fixture.adapterNodes());
        when(workerTopology.listNodeGroupBindings()).thenReturn(fixture.nodeGroupBindings());

        mockMvc.perform(get("/api/v1/catalog/worker-group-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[?(@.groupId=='group-00' && @.workerCount==25)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='group-00' && @.reachableUnlockedBindingCount > 0)]").exists())
                .andExpect(jsonPath("$.data[?(@.groupId=='group-02' && @.lockedCount > 0)]").exists());

        verify(workerQueries, times(1)).getAllWorkers();
        verify(workerQueries, times(1)).listReachableWorkerIds();
        verify(runtimeDiagnostics, times(1)).listLockedWorkerIds();
        verify(workerTopology, times(1)).listWorkerGroups();
        verify(workerTopology, times(1)).listAdapterNodes();
        verify(workerTopology, times(1)).listNodeGroupBindings();
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
                workerId.startsWith("crawler") ? "crawler" : workerId.startsWith("chat") ? "chat" : null,
                workerId.startsWith("crawler") ? "node-crawler" : workerId.startsWith("chat") ? "node-chat" : null,
                adapterId,
                transportHint,
                1,
                java.util.Map.of(),
                null,
                null
        );
    }

    private LargeWorkerFixture largeWorkerFixture(int workerCount, int groupCount) {
        List<WorkerSnapshot> workers = new ArrayList<>();
        List<WorkerGroupSnapshot> groups = new ArrayList<>();
        List<AdapterNodeSnapshot> adapterNodes = new ArrayList<>();
        List<NodeGroupBindingSnapshot> bindings = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            String groupId = "group-%02d".formatted(groupIndex);
            String adapterNodeId = "node-%02d".formatted(groupIndex);
            groups.add(new WorkerGroupSnapshot(
                    groupId,
                    List.of(WorkerEventBinding.builder()
                            .eventCode("crawler.fetch-page")
                            .projectCodes(List.of("crawlerApp", "demoApp"))
                            .build()),
                    List.of("crawlerApp", "demoApp"),
                    Map.of("fixture", "bounded-fanout"),
                    2));
            adapterNodes.add(new AdapterNodeSnapshot(
                    adapterNodeId,
                    "polling",
                    "1",
                    "endpoint-" + adapterNodeId,
                    true,
                    true,
                    null,
                    null,
                    Map.of()));
            bindings.add(new NodeGroupBindingSnapshot(
                    adapterNodeId,
                    groupId,
                    "1",
                    "bounded-fanout",
                    true,
                    false,
                    null,
                    null,
                    Map.of()));
        }
        for (int index = 1; index <= workerCount; index++) {
            String workerId = "worker-%04d".formatted(index);
            int groupIndex = (index - 1) % groupCount;
            workers.add(new WorkerSnapshot(
                    workerId,
                    index % 3 == 0 ? "OFFLINE" : "ONLINE",
                    "1.2.%d".formatted(index % 10),
                    null,
                    List.of("legacyProject"),
                    List.of("legacy.event"),
                    List.of(),
                    "group-%02d".formatted(groupIndex),
                    "node-%02d".formatted(groupIndex),
                    "polling",
                    "polling",
                    2,
                    Map.of(
                            "fingerprintProfile", "fp-%02d".formatted(index % 4),
                            "fixture", "bounded-fanout"
                    ),
                    null,
                    null
            ));
        }
        return new LargeWorkerFixture(
                List.copyOf(workers),
                List.copyOf(groups),
                List.copyOf(adapterNodes),
                List.copyOf(bindings),
                reachableWorkerIds(workers, 2),
                List.of("worker-0003", "worker-0042", "worker-0099"),
                sessionFacts(workers, 5)
        );
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

    private record LargeWorkerFixture(
            List<WorkerSnapshot> workers,
            List<WorkerGroupSnapshot> groups,
            List<AdapterNodeSnapshot> adapterNodes,
            List<NodeGroupBindingSnapshot> nodeGroupBindings,
            List<String> reachableWorkerIds,
            List<String> lockedWorkerIds,
            List<Map<String, Object>> sessions) {
    }
}
