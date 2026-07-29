package com.xa.mass.server.api.v1.workerdelivery;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource.WORKER;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService.SeedResultAppendCounts;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class AdapterBatchDeliveryControllerTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
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
                        "$.workerCommandsByWorkerId.worker-1.commandId"
                ).value(COMMAND_ID));
    }

    @Test
    void adapterResultBatchUsesTheStableResponse() throws Exception {
        String encodedResult = successResult();
        when(service.appendAdapterResults(
                eq("endpoint-1"),
                eq(WORKER),
                anyList()
        )).thenReturn(new SeedResultAppendCounts(1, 0));

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Jsons.toJson(Map.of(
                                "source",
                                "WORKER",
                                "results",
                                java.util.List.of(encodedResult)
                        ))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(0));

        verify(service).appendAdapterResults(
                "endpoint-1",
                WORKER,
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
                                "{\"source\":\"WORKER\",\"results\":[]}"
                        ))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"results\":[\"opaque\"]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(batchPath("results:append"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"source\":\"WORKER\",\"results\":["
                                        + successResult()
                                        + "]}"
                        ))
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

    private static WorkerCommandEnvelope command() {
        return new WorkerCommandEnvelope(
                COMMAND_ID,
                WorkerMessageType.TASK_ITEM,
                9_999_999_999_999L,
                "opaque-item"
        );
    }

    private static String successResult() {
        return """
                {"commandId":"%s","opaqueResultContext":"context",\
                "outcomeCode":"200","opaqueResultPayload":"null"}\
                """.formatted(COMMAND_ID);
    }

    private static String batchPath(String action) {
        return "/api/v1/worker-delivery/endpoint-managers/endpoint-1/"
                + action;
    }

}
