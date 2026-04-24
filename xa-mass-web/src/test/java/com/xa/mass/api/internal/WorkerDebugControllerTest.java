package com.xa.mass.api.internal;

import com.xa.mass.sdk.DebugOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerDebugControllerTest {

    @Mock
    private DebugOperations debugOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkerDebugController(debugOperations))
                .setControllerAdvice(new com.xa.mass.api.aop.GlobalExceptionHandler())
                .build();
    }

    @Test
    void historyUsesSdkDebugFacade() throws Exception {
        doReturn(List.of(Map.of("eventCode", "mock.state.get")))
                .when(debugOperations).getWorkerMessageHistory("worker-1");

        mockMvc.perform(get("/status/workers/message-history").param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("worker-1"))
                .andExpect(jsonPath("$.data.items[0].eventCode").value("mock.state.get"));
    }

    @Test
    void sendEventMapsWorkerNotFoundTo404() throws Exception {
        when(debugOperations.sendWorkerEvent(eq("worker-1"), any(), any()))
                .thenThrow(new IllegalArgumentException("Worker not found"));

        mockMvc.perform(post("/status/workers/send-event")
                        .contentType("application/json")
                        .content("""
                                {"workerId":"worker-1","event":"mock.state.get","payload":{}}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("Worker not found"));
    }

    @Test
    void sendEventPassesCanonicalEventRequestAndReturnsEventFirstResult() throws Exception {
        when(debugOperations.sendWorkerEvent(eq("worker-1"), any(), any()))
                .thenReturn(Map.of(
                        "messageId", "msg-1",
                        "workerId", "worker-1",
                        "eventCode", "mock.state.get",
                        "requestId", "req-1"
                ));

        mockMvc.perform(post("/status/workers/send-event")
                        .contentType("application/json")
                        .content("""
                                {
                                  "workerId":"worker-1",
                                  "project":"demoApp",
                                  "event":"mock.state.get",
                                  "requestId":"req-1",
                                  "headers":{"mode":"probe"},
                                  "payload":{"verbose":true},
                                  "principal":{"clientId":"ops-console","userId":"operator-1"}
                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.eventCode").value("mock.state.get"));

        ArgumentCaptor<com.xa.mass.sdk.event.EventRequest> requestCaptor =
                ArgumentCaptor.forClass(com.xa.mass.sdk.event.EventRequest.class);
        ArgumentCaptor<com.xa.mass.sdk.event.EventPrincipal> principalCaptor =
                ArgumentCaptor.forClass(com.xa.mass.sdk.event.EventPrincipal.class);
        verify(debugOperations).sendWorkerEvent(eq("worker-1"), requestCaptor.capture(), principalCaptor.capture());

        assertEquals("mock.state.get", requestCaptor.getValue().getEvent().value());
        assertEquals("demoApp", requestCaptor.getValue().getProject());
        assertEquals("req-1", requestCaptor.getValue().getRequestId());
        assertEquals(Map.of("mode", "probe"), requestCaptor.getValue().getHeaders());
        assertEquals(Map.of("verbose", true), requestCaptor.getValue().getPayload());
        assertEquals("ops-console", principalCaptor.getValue().getClientId());
        assertEquals("operator-1", principalCaptor.getValue().getUserId());
    }
}
