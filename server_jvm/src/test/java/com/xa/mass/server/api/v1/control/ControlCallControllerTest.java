package com.xa.mass.server.api.v1.control;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.WorkerControlController;
import com.xa.mass.server.api.v1.workerdelivery.AdapterControlController;
import com.xa.mass.server.control.ControlCallProperties;
import com.xa.mass.server.control.ControlCallRegistry;
import com.xa.mass.server.control.ControlCallService;
import com.xa.mass.server.workerbinding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
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
    private static final String ADAPTER_1 = "adapter-1";
    private static final String ADAPTER_2 = "adapter-2";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        WorkerEndpointDirectory endpoints = new WorkerEndpointDirectory(
                Map.of(
                        ADAPTER_1,
                        endpoint(18083),
                        ADAPTER_2,
                        endpoint(18084)
                )
        );
        List<String> workerIds = List.of(WORKER_1, WORKER_2);
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds))
                .thenReturn(Map.of(
                        WORKER_1, descriptor(WORKER_1, ADAPTER_1),
                        WORKER_2, descriptor(WORKER_2, ADAPTER_2)
                ));
        when(scores.getScoreStates(GROUP_ID, workerIds))
                .thenReturn(Map.of(
                        WORKER_1, paused(WORKER_1),
                        WORKER_2, paused(WORKER_2)
                ));
        when(bindings.currentEndpointManagerIds(workerIds))
                .thenReturn(Map.of(
                        WORKER_1, ADAPTER_1,
                        WORKER_2, ADAPTER_2
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
                new WorkerDeliveryCodec(),
                new ControlCallRegistry(properties),
                properties
        );
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkerControlController(service),
                        new AdapterControlController(service)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void workerBatchCompletesAcrossTwoAdapterResultBatches()
            throws Exception {
        MvcResult call = mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(List.of(
                                WORKER_1,
                                WORKER_2
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();

        Map<String, Object> command1 = consume(ADAPTER_1, WORKER_1);
        Map<String, Object> command2 = consume(ADAPTER_2, WORKER_2);
        appendWorkerResult(ADAPTER_2, WORKER_2, command2, "200");
        appendWorkerResult(ADAPTER_1, WORKER_1, command1, "3302");

        mockMvc.perform(asyncDispatch(call))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath(
                        "$.results['" + WORKER_1 + "'].status"
                ).value("observed"))
                .andExpect(jsonPath(
                        "$.results['" + WORKER_1 + "'].outcomeCode"
                ).value("3302"))
                .andExpect(jsonPath(
                        "$.results['" + WORKER_2 + "'].status"
                ).value("observed"));
    }

    @Test
    void adapterCallUsesTheSameAggregateResponse() throws Exception {
        MvcResult call = mockMvc.perform(post(controlPath(
                                ADAPTER_1,
                                "controls:call"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "messageType", "adapter.probe",
                                "opaquePayload", "null",
                                "waitTimeoutMillis", 3_000
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult consumed = mockMvc.perform(post(controlPath(
                                ADAPTER_1,
                                "control-commands:consume"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> command = command(
                consumed,
                ControlCallRegistry.ADAPTER_TARGET_ADDRESS
        );
        mockMvc.perform(post(controlPath(
                                ADAPTER_1,
                                "control-results:append"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBatch(
                                "ADAPTER",
                                ADAPTER_1,
                                "adapter.probe",
                                (String) command.get("forward"),
                                "200"
                        )))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));

        mockMvc.perform(asyncDispatch(call))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath(
                        "$.results['" + ADAPTER_1 + "'].status"
                ).value("observed"));
    }

    @Test
    void invalidBatchShapesAndOldSingleWorkerPathAreRejected()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(List.of())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(List.of(
                                WORKER_1,
                                WORKER_1
                        ))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(IntStream.range(0, 101)
                                .mapToObj(index -> "worker-" + index)
                                .toList())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "workerIds", List.of(WORKER_1),
                                "messageType", "event",
                                "opaquePayload", "{}",
                                "unknown", true
                        ))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                                "/api/v1/worker-groups/" + GROUP_ID
                                        + "/workers/" + WORKER_1
                                        + "/controls:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private Map<String, Object> consume(
            String adapterId,
            String workerId
    ) throws Exception {
        MvcResult consumed = mockMvc.perform(post(controlPath(
                                adapterId,
                                "control-commands:consume"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.commands['" + workerId + "'].dst"
                ).value("WORKER"))
                .andReturn();
        return command(consumed, workerId);
    }

    private void appendWorkerResult(
            String adapterId,
            String workerId,
            Map<String, Object> command,
            String outcomeCode
    ) throws Exception {
        mockMvc.perform(post(controlPath(
                                adapterId,
                                "control-results:append"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultBatch(
                                "WORKER",
                                workerId,
                                "worker.properties.snapshot",
                                (String) command.get("forward"),
                                outcomeCode
                        )))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1));
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
        request.put("workerIds", workerIds);
        request.put("messageType", "worker.properties.snapshot");
        request.put("opaquePayload", "{}");
        request.put("waitTimeoutMillis", 3_000);
        return Jsons.toJson(request);
    }

    private static String resultBatch(
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
        return Jsons.toJson(Map.of(
                "results",
                List.of(Jsons.toJson(report))
        ));
    }

    private static WorkerDescriptor descriptor(
            String workerId,
            String adapterId
    ) {
        return new WorkerDescriptor(
                workerId,
                GROUP_ID,
                adapterId,
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

    private static String controlPath(String adapterId, String action) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + adapterId + "/" + action;
    }
}
