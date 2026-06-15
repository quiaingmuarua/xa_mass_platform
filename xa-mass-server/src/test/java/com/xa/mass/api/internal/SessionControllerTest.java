package com.xa.mass.api.internal;

import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
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
    private RuntimeDiagnosticsOperations runtimeDiagnostics;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(runtimeDiagnostics)).build();
    }

    @Test
    void listSessionsReturnsTransportNeutralEndpointSnapshots() throws Exception {
        when(runtimeDiagnostics.listSessions()).thenReturn(List.of(Map.of(
                "workerId", "worker-1",
                "connections", List.of(Map.of(
                        "active", true,
                        "endpointId", "endpoint-1",
                        "routeKey", "route-1",
                        "adapterId", "ws-public"
                ))
        )));

        mockMvc.perform(get("/api/v1/runtime/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$.data[0].connections[0].active").value(true))
                .andExpect(jsonPath("$.data[0].connections[0].endpointId").value("endpoint-1"))
                .andExpect(jsonPath("$.data[0].connections[0].routeKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].connections[0].adapterId").doesNotExist());
    }

    @Test
    void listSessionsAppliesDiagnosticResponseLimit() throws Exception {
        when(runtimeDiagnostics.listSessions()).thenReturn(List.of(
                Map.of(
                        "workerId", "worker-1",
                        "connections", List.of()
                ),
                Map.of(
                        "workerId", "worker-2",
                        "connections", List.of()
                )
        ));

        mockMvc.perform(get("/api/v1/runtime/sessions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-1"));
    }

    @Test
    void sessionStatsUseSdkCounts() throws Exception {
        when(runtimeDiagnostics.getSessionStats()).thenReturn(Map.of(
                "activeConnections", 2,
                "workerCount", 2L,
                "activeConnectionsByAdapter", Map.of("ws-public", 1L, "socket-edge", 1L)
        ));

        mockMvc.perform(get("/api/v1/runtime/sessions:stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activeConnections").value(2))
                .andExpect(jsonPath("$.data.workerCount").value(2))
                .andExpect(jsonPath("$.data.activeConnectionsByAdapter").doesNotExist());
    }
}
