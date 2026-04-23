package com.xa.mass.api.internal;

import com.xa.mass.sdk.TransportOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private TransportOperations transportOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(transportOperations)).build();
    }

    @Test
    void listSessionsReturnsTransportNeutralEndpointSnapshots() throws Exception {
        when(transportOperations.listSessions()).thenReturn(List.of(Map.of(
                "workerId", "worker-1",
                "connections", List.of(Map.of(
                        "role", "task_messages",
                        "active", true,
                        "endpointId", "endpoint-1",
                        "transport", "websocket"
                ))
        )));

        mockMvc.perform(get("/api/session/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$.data[0].connections[0].role").value("task_messages"))
                .andExpect(jsonPath("$.data[0].connections[0].active").value(true))
                .andExpect(jsonPath("$.data[0].connections[0].endpointId").value("endpoint-1"))
                .andExpect(jsonPath("$.data[0].connections[0].transport").value("websocket"));
    }

    @Test
    void sessionStatsUseSdkCounts() throws Exception {
        when(transportOperations.getSessionStats()).thenReturn(Map.of(
                "activeConnections", 2,
                "workerCount", 2L
        ));

        mockMvc.perform(get("/api/session/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activeConnections").value(2))
                .andExpect(jsonPath("$.data.workerCount").value(2));
    }
}
