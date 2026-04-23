package com.xa.mass.api.internal;

import com.xa.mass.sdk.DebugOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
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
        doReturn(List.of(Map.of("msgType", "CONTROL")))
                .when(debugOperations).getWorkerMessageHistory("worker-1");

        mockMvc.perform(get("/status/workers/message-history").param("workerId", "worker-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workerId").value("worker-1"))
                .andExpect(jsonPath("$.data.items[0].msgType").value("CONTROL"));
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
}
