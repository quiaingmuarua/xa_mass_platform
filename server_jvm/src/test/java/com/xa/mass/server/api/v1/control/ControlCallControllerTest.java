package com.xa.mass.server.api.v1.control;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.workerdelivery.AdapterBatchDeliveryController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterControlController;
import com.xa.mass.server.control.ControlCallProperties;
import com.xa.mass.server.control.ControlCallRegistry;
import com.xa.mass.server.control.ControlCallService;
import com.xa.mass.server.workerbinding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ControlCallControllerTest {

    private static final String GROUP_ID = "group-1";
    private static final String WORKER_1 =
            "b93ad1ab-313d-4484-838b-52f9dbc975ac";
    private static final String WORKER_2 =
            "5edc3086-9b45-4b47-8d24-f34e44f8dcd9";
    private static final String ADAPTER_ID = "adapter-1";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        WorkerEndpointDirectory endpoints = new WorkerEndpointDirectory(
                Map.of(ADAPTER_ID, endpoint(18083))
        );
        List<String> workerIds = List.of(WORKER_1, WORKER_2);
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(Map.of(
                        WORKER_1, descriptor(WORKER_1),
                        WORKER_2, descriptor(WORKER_2)
                ));
        when(scores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(Map.of(
                        WORKER_1, paused(WORKER_1),
                        WORKER_2, paused(WORKER_2)
                ));
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(Map.of(
                        WORKER_1, ADAPTER_ID,
                        WORKER_2, ADAPTER_ID
                ));
        ControlCallProperties properties = new ControlCallProperties(
                3_000,
                10_000,
                1_000,
                10_000
        );
        ControlCallService service = new ControlCallService(
                catalog,
                scores,
                bindings,
                endpoints,
                new ControlCallRegistry(properties),
                properties
        );
        WorkerDeliveryService workerDelivery = new WorkerDeliveryService(
                mock(WorkerCommandRuntime.class),
                mock(WorkerResultRuntime.class),
                bindings,
                service
        );
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdapterControlController(service),
                        new AdapterBatchDeliveryController(workerDelivery)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void workerBatchCompletesThroughTheSelectedAdapter() throws Exception {
        MvcResult call = mockMvc.perform(post(controlPath("controls:call"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(List.of(
                                WORKER_1,
                                WORKER_2
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult consumed = mockMvc.perform(post(controlPath(
                                "commands:consume"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.commands['" + WORKER_1 + "'].dst"
                ).value("WORKER"))
                .andExpect(jsonPath(
                        "$.commands['" + WORKER_2 + "'].dst"
                ).value("WORKER"))
                .andExpect(jsonPath(
                        "$.commands['" + WORKER_1 + "'].payload"
                ).value("{\"workerId\":\"" + WORKER_1 + "\"}"))
                .andExpect(jsonPath(
                        "$.commands['" + WORKER_2 + "'].payload"
                ).value("{\"workerId\":\"" + WORKER_2 + "\"}"))
                .andReturn();
        Map<String, Object> command1 = command(consumed, WORKER_1);
        Map<String, Object> command2 = command(consumed, WORKER_2);
        mockMvc.perform(post(controlPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBatch(List.of(
                                encodedWorkerResult(
                                        WORKER_1,
                                        command1,
                                        "3302"
                                ),
                                encodedWorkerResult(
                                        WORKER_2,
                                        command2,
                                        "200"
                                )
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(2));

        mockMvc.perform(asyncDispatch(call))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath(
                        "$.results['" + WORKER_1 + "'].outcomeCode"
                ).value("3302"))
                .andExpect(jsonPath(
                        "$.results['" + WORKER_2 + "'].status"
                ).value("observed"));
    }

    @Test
    void omittedWorkerCoordinatesCallTheAdapter() throws Exception {
        MvcResult call = mockMvc.perform(post(controlPath("controls:call"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "messageType",
                                "platform.adapter.events.snapshot",
                                "opaquePayload", "null",
                                "waitTimeoutMillis", 3_000
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult consumed = mockMvc.perform(post(controlPath(
                                "commands:consume"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> command = command(
                consumed,
                ControlCallRegistry.ADAPTER_TARGET_ADDRESS
        );
        mockMvc.perform(post(controlPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBatch(List.of(encodedResult(
                                "ADAPTER",
                                ADAPTER_ID,
                                "platform.adapter.events.snapshot",
                                (String) command.get("forward"),
                                "200"
                        )))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));

        mockMvc.perform(asyncDispatch(call))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath(
                        "$.results['" + ADAPTER_ID + "'].status"
                ).value("observed"));
    }

    @Test
    void invalidShapesAndRetiredWorkerGroupRouteAreRejected()
            throws Exception {
        assertBadRequest(workerBatchRequest(List.of()));
        assertBadRequest(workerBatchRequest(IntStream.range(0, 101)
                .mapToObj(index -> "worker-" + index)
                .toList()));
        assertBadRequest(Jsons.toJson(Map.of(
                "workerGroupId", GROUP_ID,
                "messageType", "event",
                "opaquePayload", "{}"
        )));
        assertBadRequest(Jsons.toJson(Map.of(
                "workerPayloads", Map.of(WORKER_1, "{}"),
                "messageType", "event",
                "waitTimeoutMillis", 3_000
        )));
        assertBadRequest(Jsons.toJson(Map.of(
                "workerGroupId", GROUP_ID,
                "workerIds", List.of(WORKER_1),
                "messageType", "event",
                "opaquePayload", "{}",
                "waitTimeoutMillis", 3_000
        )));
        assertBadRequest(Jsons.toJson(Map.of(
                "workerGroupId", GROUP_ID,
                "workerPayloads", Map.of(WORKER_1, "{}"),
                "messageType", "event",
                "opaquePayload", "{}",
                "waitTimeoutMillis", 3_000
        )));
        assertBadRequest("""
                {
                  "workerGroupId":"group-1",
                  "workerPayloads":{"worker-1":null},
                  "messageType":"event",
                  "waitTimeoutMillis":3000
                }
                """);
        assertBadRequest(Jsons.toJson(Map.of(
                "workerGroupId", GROUP_ID,
                "workerPayloads", Map.of(WORKER_1, "{}"),
                "messageType", "event",
                "waitTimeoutMillis", 3_000,
                "unknown", true
        )));
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(post(controlPath("controls:call"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> command(
            MvcResult consumed,
            String address
    ) throws Exception {
        Map<String, Object> body = Jsons.parseObject(
                consumed.getResponse().getContentAsString()
        );
        Map<String, Object> commands =
                (Map<String, Object>) body.get("commands");
        return (Map<String, Object>) commands.get(address);
    }

    private static String workerBatchRequest(List<String> workerIds) {
        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        LinkedHashMap<String, String> workerPayloads = new LinkedHashMap<>();
        workerIds.forEach(workerId -> workerPayloads.put(
                workerId,
                "{\"workerId\":\"" + workerId + "\"}"
        ));
        request.put("workerGroupId", GROUP_ID);
        request.put("workerPayloads", workerPayloads);
        request.put(
                "messageType",
                "platform.worker.events.snapshot"
        );
        request.put("waitTimeoutMillis", 3_000);
        return Jsons.toJson(request);
    }

    private static String encodedWorkerResult(
            String workerId,
            Map<String, Object> command,
            String outcomeCode
    ) {
        return encodedResult(
                "WORKER",
                workerId,
                "platform.worker.events.snapshot",
                (String) command.get("forward"),
                outcomeCode
        );
    }

    private static String encodedResult(
            String source,
            String sourceId,
            String event,
            String forward,
            String outcomeCode
    ) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("src", source);
        report.put("sourceId", sourceId);
        report.put("dst", "SYSTEM");
        report.put("messageType", event);
        report.put("outcomeCode", outcomeCode);
        report.put("payload", "{\"ok\":true}");
        report.put("forward", forward);
        return Jsons.toJson(report);
    }

    private static String resultBatch(List<String> reports) {
        return Jsons.toJson(Map.of("results", reports));
    }

    private static WorkerDescriptor descriptor(String workerId) {
        return new WorkerDescriptor(
                workerId,
                GROUP_ID,
                ADAPTER_ID,
                Map.of(),
                Map.of()
        );
    }

    private static WorkerScoreState paused(String workerId) {
        return new WorkerScoreState(
                workerId,
                1,
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                0,
                0
        );
    }

    private static EndpointProperties endpoint(int port) {
        return new EndpointProperties(
                WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:" + port + "/worker")
        );
    }

    private static String controlPath(String action) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + ADAPTER_ID + "/" + action;
    }
}
