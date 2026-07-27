package com.xa.mass.server.workerdelivery.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.workerdelivery.WorkerDeliveryException;
import com.xa.mass.server.workerdelivery.WorkerDeliveryAccessPolicy;
import com.xa.mass.server.workerdelivery.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.websocket.WorkerWebSocketProperties;
import java.time.Duration;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkerPointDeliveryControllerTest {

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
                        new WorkerPointDeliveryController(
                                service,
                                accessPolicy(false, "")
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void configuredWebSocketEndpointIsReservedFromPointHttp()
            throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new WorkerPointDeliveryController(
                                service,
                                accessPolicy(true, "endpoint-1")
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();

        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pointPollReturnsEmptyOrTheExactEnvelope() throws Exception {
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isNoContent());

        when(service.pollWorkerCommand("endpoint-1", "worker-1"))
                .thenReturn(command());
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(COMMAND_ID))
                .andExpect(jsonPath("$.messageType").value("TASK_ITEM"))
                .andExpect(jsonPath("$.executeBeforeMillis")
                        .value(9_999_999_999_999L))
                .andExpect(jsonPath("$.opaqueItem").value("opaque-item"));
    }

    @Test
    void pointResultUsesTheStableResponse() throws Exception {
        mockMvc.perform(post(pointPath("results"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successResult()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));

        verify(service).appendWorkerResult(
                eq("endpoint-1"),
                eq("worker-1"),
                any()
        );
    }

    @Test
    void invalidAndUnavailableRequestsUseTheServerErrorContract()
            throws Exception {
        mockMvc.perform(post(pointPath("results"))
                        .header("X-Request-Id", "invalid-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successResult().replace(COMMAND_ID, "bad")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_WORKER_DELIVERY_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("invalid-result"));

        mockMvc.perform(post(pointPath("results"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successResult().replace(
                                "}",
                                ",\"unexpected\":true}"
                        )))
                .andExpect(status().isBadRequest());

        when(service.pollWorkerCommand("endpoint-1", "worker-1"))
                .thenThrow(WorkerDeliveryException.unavailable(
                        new IllegalStateException("offline")
                ));
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("WORKER_DELIVERY_UNAVAILABLE"));
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

    private static String pointPath(String action) {
        return "/api/v1/worker-delivery/endpoint-managers/endpoint-1"
                + "/workers/worker-1/" + action;
    }

    private static WorkerDeliveryAccessPolicy accessPolicy(
            boolean enabled,
            String endpointManagerId
    ) {
        return new WorkerDeliveryAccessPolicy(new WorkerWebSocketProperties(
                enabled,
                endpointManagerId,
                Duration.ofMillis(100),
                100,
                100,
                1000,
                Duration.ofSeconds(5)
        ));
    }
}
