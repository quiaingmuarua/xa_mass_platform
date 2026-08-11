package com.xa.mass.server.api.v1.workerdelivery;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService.WorkerResultAppendCounts;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AdapterBatchDeliveryControllerTest {

    private static final DeliveryCommand COMMAND = DeliveryCommand.create(
            DeliveryEndpoint.TASK,
            DeliveryEndpoint.WORKER,
            "test.event",
            9_999_999_999_999L,
            "opaque-item",
            "context"
    );
    private WorkerDeliveryService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(WorkerDeliveryService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdapterBatchDeliveryController(service)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void adapterBatchPreservesWorkerDemux() throws Exception {
        when(service.consumeWorkerCommands("endpoint-1", 100))
                .thenReturn(Map.of("worker-1", command()));

        mockMvc.perform(post(batchPath("commands:consume"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.workerCommandsByWorkerId.worker-1.messageId"
                ).doesNotExist())
                .andExpect(jsonPath(
                        "$.workerCommandsByWorkerId.worker-1.messageType"
                ).value("test.event"));
    }

    @Test
    void adapterResultBatchUsesTheStableResponse() throws Exception {
        String encodedResult = successResult();
        when(service.appendAdapterResults(
                org.mockito.ArgumentMatchers.eq("endpoint-1"),
                anyList()
        )).thenReturn(new WorkerResultAppendCounts(1, 0));

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "results",
                                java.util.List.of(encodedResult)
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(0));

        verify(service).appendAdapterResults(
                "endpoint-1",
                java.util.List.of(encodedResult)
        );
    }

    @Test
    void systemPollingAndMalformedBatchesAreRejected() throws Exception {
        when(service.consumeWorkerCommands("system-polling", 100))
                .thenThrow(new ServerException(
                        ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST,
                        "workerDelivery.consumeCommands",
                        "system-polling supports only point Worker access",
                        null
                ));

        mockMvc.perform(post(
                        "/api/v1/worker-delivery/endpoint-managers/"
                        + "system-polling/commands:consume"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":100}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"results\":[]}"
                        ))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"source\":\"WORKER\","
                                        + "\"results\":[\"opaque\"]}"
                        ))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"results\":[null]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("commands:consume"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"limit\":100,\"unexpected\":true}"
                        ))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("commands:consume"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cursor\":null,\"scanCount\":100}"))
                .andExpect(status().isBadRequest());
    }

    private static DeliveryCommand command() {
        return COMMAND;
    }

    private static String successResult() {
        return """
                {"dst":"TASK","forward":"context",\
                "messageType":"test.event","outcomeCode":"200",\
                "payload":"null","sourceId":"worker-1","src":"WORKER"}\
                """;
    }

    private static String batchPath(String action) {
        return "/api/v1/worker-delivery/endpoint-managers/endpoint-1/"
                + action;
    }

}
