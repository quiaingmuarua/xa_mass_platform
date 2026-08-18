package com.xa.mass.server.api.v1.workerdelivery;

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
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class WorkerPointDeliveryControllerTest {

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
                        new WorkerPointDeliveryController(service)
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void pointPollReturnsEmptyOrTheExactEnvelope() throws Exception {
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isNoContent());

        when(service.pollWorkerCommand("endpoint-1", "worker-1"))
                .thenReturn(command());
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").doesNotExist())
                .andExpect(jsonPath("$.src").value("TASK"))
                .andExpect(jsonPath("$.dst").value("WORKER"))
                .andExpect(jsonPath("$.messageType").value("test.event"))
                .andExpect(jsonPath("$.executeBeforeMillis")
                        .value(9_999_999_999_999L))
                .andExpect(jsonPath("$.payload").value("opaque-item"))
                .andExpect(jsonPath("$.forward").value("context"));
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
    void routeVerificationUsesWorkerBindingAddress() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/worker-delivery/endpoint-managers/"
                                + "endpoint-1/workers/"
                                + "worker-1:verify-binding"
                ))
                .andExpect(status().isNoContent());

        verify(service).verifyWorkerRoute(
                "endpoint-1",
                "worker-1"
        );
    }

    @Test
    void invalidAndUnavailableRequestsUseTheServerErrorContract()
            throws Exception {
        mockMvc.perform(post(pointPath("results"))
                        .header("X-Request-Id", "invalid-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successResult().replace(
                                "}",
                                ",\"messageId\":\"legacy\"}"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").value("invalid-result"));

        mockMvc.perform(post(pointPath("results"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successResult().replace(
                                "}",
                                ",\"unexpected\":true}"
                        )))
                .andExpect(status().isBadRequest());

        when(service.pollWorkerCommand("endpoint-1", "worker-1"))
                .thenThrow(new ServerException(
                        ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE,
                        "workerDelivery.pollCommand",
                        null,
                        new IllegalStateException("offline")
                ));
        mockMvc.perform(post(pointPath("commands:poll")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value(13002));
    }

    private static DeliveryCommand command() {
        return COMMAND;
    }

    private static String successResult() {
        return """
                {"src":"WORKER","sourceId":"worker-1",\
                "dst":"TASK","messageType":"test.event",\
                "outcomeCode":"200","payload":"null","forward":"context"}\
                """;
    }

    private static String pointPath(String action) {
        return "/api/v1/worker-delivery/endpoint-managers/endpoint-1"
                + "/workers/worker-1/" + action;
    }

}
