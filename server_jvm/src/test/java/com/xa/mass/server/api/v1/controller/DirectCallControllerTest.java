package com.xa.mass.server.api.v1.controller;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.delivery.directcall.DirectCallProperties;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.delivery.directcall.DirectCallService;
import com.xa.mass.server.worker.binding.WorkerBindingProperties.EndpointProperties;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.server.delivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class DirectCallControllerTest {

    private static final String GROUP_ID = "group-1";
    private static final String WORKER_1 = "worker-1";
    private static final String WORKER_2 = "worker-2";
    private static final String ADAPTER_ID = "adapter-1";

    private MockMvc mockMvc;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    @BeforeEach
    void setUp() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerCommandRuntime commandRuntime = mock(WorkerCommandRuntime.class);
        TaskResultRuntime resultRuntime = mock(TaskResultRuntime.class);
        WorkerBindingService bindings = mock(WorkerBindingService.class);
        List<String> workerIds = List.of(WORKER_1, WORKER_2);
        when(catalog.getWorkerDescriptors(GROUP_ID, workerIds)).thenReturn(
                Map.of(
                        WORKER_1, descriptor(WORKER_1),
                        WORKER_2, descriptor(WORKER_2)
                )
        );
        when(bindings.currentEndpointManagerIds(workerIds)).thenReturn(
                Map.of(WORKER_1, ADAPTER_ID, WORKER_2, ADAPTER_ID)
        );

        AtomicReference<Map<String, DeliveryCommand>> mailbox =
                new AtomicReference<>(Map.of());
        when(commandRuntime.offerWorkerCommands(eq(ADAPTER_ID), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, DeliveryCommand> offered =
                            invocation.getArgument(1);
                    mailbox.set(new LinkedHashMap<>(offered));
                    LinkedHashMap<String, WorkerCommandOfferStatus> statuses =
                            new LinkedHashMap<>();
                    offered.keySet().forEach(workerId -> statuses.put(
                            workerId,
                            WorkerCommandOfferStatus.OFFERED
                    ));
                    return statuses;
                });
        when(commandRuntime.consumeWorkerCommands(ADAPTER_ID, 100))
                .thenAnswer(ignored -> mailbox.getAndSet(Map.of()));

        DirectCallProperties properties = new DirectCallProperties(
                3_000,
                10_000,
                1_000,
                10_000
        );
        DirectCallService directCalls = new DirectCallService(
                catalog,
                commandRuntime,
                bindings,
                new WorkerEndpointDirectory(Map.of(
                        ADAPTER_ID,
                        new EndpointProperties(
                                WorkerTransportType.WEBSOCKET,
                                URI.create("ws://127.0.0.1:18083/worker")
                        )
                )),
                new DirectCallRegistry(properties),
                properties
        );
        WorkerDeliveryService workerDelivery = new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                bindings,
                directCalls,
                mock(WorkerServiceabilityRuntime.class)
        );
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdapterDirectCallController(directCalls),
                        new AdapterBatchDeliveryController(workerDelivery)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void workerBatchCompletesThroughUnifiedDeliveryApis() throws Exception {
        MvcResult call = mockMvc.perform(post(path("direct-calls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workerBatchRequest(List.of(
                                WORKER_1,
                                WORKER_2
                        ))))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult consumed = mockMvc.perform(post(path("commands:consume"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['worker-1'].src")
                        .value("SYSTEM"))
                .andExpect(jsonPath("$['worker-2'].dst")
                        .value("WORKER"))
                .andReturn();
        Map<String, Object> response = Jsons.parseObject(
                consumed.getResponse().getContentAsString()
        );
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> commands =
                (Map<String, Map<String, Object>>) (Map<?, ?>) response;
        List<String> reports = commands.entrySet().stream()
                .map(entry -> codec.encodeDeliveryReport(DeliveryReport.create(
                        DeliveryEndpoint.WORKER,
                        entry.getKey(),
                        DeliveryEndpoint.SYSTEM,
                        String.valueOf(entry.getValue().get("messageType")),
                        "200",
                        "{\"reachable\":true}",
                        String.valueOf(entry.getValue().get("forward"))
                )))
                .toList();
        mockMvc.perform(post(path("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(reports)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.rejectedCount").value(0));

        mockMvc.perform(asyncDispatch(call))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directCallId").isString())
                .andExpect(jsonPath("$.controlBatchId").doesNotExist())
                .andExpect(jsonPath("$.status").value("observed"))
                .andExpect(jsonPath("$.results['worker-1'].status")
                        .value("observed"))
                .andExpect(jsonPath("$.results['worker-2'].status")
                        .value("observed"));
    }

    @Test
    void oldRouteAndInvalidBatchShapesAreRejected() throws Exception {
        mockMvc.perform(post(path("controls:call"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        IntStream.range(0, 101).forEach(index -> payloads.put(
                "worker-" + index,
                "{}"
        ));
        mockMvc.perform(post(path("direct-calls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "workerGroupId", GROUP_ID,
                                "workerPayloads", payloads,
                                "messageType", "extension.worker.inspect"
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(path("direct-calls"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageType\":\"event\","
                                + "\"opaquePayload\":\"{}\","
                                + "\"legacy\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAdapterIsABusinessRejection() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "missing-adapter/direct-calls"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageType\":\"event\","
                                + "\"opaquePayload\":\"{}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(17002))
                .andExpect(jsonPath("$.message")
                        .value("Direct Call target was not found"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    private static String workerBatchRequest(List<String> workerIds) {
        LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
        workerIds.forEach(workerId -> payloads.put(workerId, "{}"));
        return Jsons.toJson(Map.of(
                "workerGroupId", GROUP_ID,
                "workerPayloads", payloads,
                "messageType", "extension.worker.inspect",
                "waitTimeoutMillis", 3_000
        ));
    }

    private static WorkerDescriptor descriptor(String workerId) {
        return new WorkerDescriptor(
                workerId,
                GROUP_ID,
                ADAPTER_ID
        );
    }

    private static String path(String suffix) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + ADAPTER_ID + "/" + suffix;
    }
}
