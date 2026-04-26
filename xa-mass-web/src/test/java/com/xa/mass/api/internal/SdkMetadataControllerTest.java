package com.xa.mass.api.internal;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.sdk.TransportOperations;
import com.xa.mass.sdk.WorkerOperations;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.EventDefinition;
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

class SdkMetadataControllerTest {

    private MockMvc mockMvc;
    private TransportOperations transportOperations;

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
                .handler((request, principal) -> EventResponse.success(java.util.Map.of(), request.getRequestId()))
                .build());
        catalog.registerProject(ProjectMetadata.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Test demo app")
                .eventCodes(java.util.List.of("crawler.fetch-page", "chatbot.reply"))
                .build());
        Worker crawlerWorker = worker("crawler-worker-1", WorkerStatus.ONLINE,
                List.of("otherApp"), List.of("crawler.fetch-page"));
        Worker offlineChatWorker = worker("chat-worker-1", WorkerStatus.OFFLINE,
                List.of("demoApp"), List.of("chatbot.reply"));
        Worker scopeOnlyWorker = worker("scope-only-worker", WorkerStatus.ONLINE,
                List.of("demoApp"), List.of());
        WorkerOperations workerOperations = mock(WorkerOperations.class);
        when(workerOperations.getAllWorkers()).thenReturn(List.of(crawlerWorker, offlineChatWorker, scopeOnlyWorker));
        when(workerOperations.isWorkerLocked("crawler-worker-1")).thenReturn(false);
        when(workerOperations.isWorkerLocked("chat-worker-1")).thenReturn(true);
        when(workerOperations.isWorkerLocked("scope-only-worker")).thenReturn(false);
        transportOperations = mock(TransportOperations.class);
        when(transportOperations.listSessions()).thenReturn(List.of(
                java.util.Map.of(
                        "workerId", "crawler-worker-1",
                        "connections", java.util.List.of(java.util.Map.of(
                                "active", true,
                                "endpointId", "ws-crawler-1",
                                "transport", "websocket"
                        ))
                ),
                java.util.Map.of(
                        "workerId", "scope-only-worker",
                        "connections", java.util.List.of(java.util.Map.of(
                                "active", true,
                                "endpointId", "poll-1",
                                "transport", "polling"
                        ))
                )
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SdkMetadataController(catalog, workerOperations, transportOperations)
        ).build();
    }

    @Test
    void listProjectsReturnsProjectMetadata() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[0].eventCodes").isArray());
    }

    @Test
    void projectEventsReturnResolvedEventDefinitions() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects/demoApp/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.code=='chatbot.reply')]").exists());
    }

    @Test
    void listEventsReturnsEventDefinitions() throws Exception {
        mockMvc.perform(get("/sdk/meta/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.code=='sms.wait-code')]").exists());
    }

    @Test
    void eventCapabilitiesReturnInvocationModelAndLiveWorkerCoverage() throws Exception {
        mockMvc.perform(get("/sdk/meta/event-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.invocationModel=='TASK_BACKED')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.projectCodes[0]=='crawlerApp' && @.projectCodes[1]=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.workerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.onlineWorkerIds[0]=='crawler-worker-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.invocationModel=='DIRECT_RUNTIME')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='sms.wait-code' && @.hasDirectRuntimeHandler==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='chatbot.reply' && @.hasOnlineWorkerCoverage==false)]").exists())
                .andExpect(jsonPath("$.data[?(@.eventCode=='crawler.fetch-page' && @.workerIds.length()==1)]").exists());
    }

    @Test
    void workerCapabilitiesJoinCatalogWorkerAndTransportFacts() throws Exception {
        mockMvc.perform(get("/sdk/meta/worker-capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.supportedEventCodes[0]=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].eventCode=='crawler.fetch-page')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].projectCodes[0]=='crawlerApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.eventBindings[0].projectCodes[1]=='demoApp')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.connections[0].endpointId=='ws-crawler-1')]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='crawler-worker-1' && @.hasActiveEndpoint==true)]").exists())
                .andExpect(jsonPath("$.data[?(@.workerId=='chat-worker-1' && @.locked==true)]").exists());
    }

    @Test
    void missingProjectOrEventReturnsNotFound() throws Exception {
        mockMvc.perform(get("/sdk/meta/projects/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/sdk/meta/events/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private Worker worker(String workerId,
                          WorkerStatus status,
                          List<String> supportedProjects,
                          List<String> supportedEventCodes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(status);
        worker.setSupportedProjects(supportedProjects);
        worker.setSupportedEventCodes(supportedEventCodes);
        if ("crawler-worker-1".equals(workerId)) {
            worker.setAdapterId("websocket");
            worker.setOnlineStrategy("realtime");
        } else if ("scope-only-worker".equals(workerId)) {
            worker.setAdapterId("polling");
            worker.setOnlineStrategy("polling");
        }
        return worker;
    }
}
