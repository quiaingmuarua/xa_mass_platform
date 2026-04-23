package com.xa.mass.api.internal;

import com.xa.mass.sdk.TransportOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class QueueControllerTest {

    @Mock
    private TransportOperations transportOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new QueueController(transportOperations)).build();
    }

    @Test
    void queueStatusUsesSdkTransportFacade() throws Exception {
        when(transportOperations.getQueueDetail()).thenReturn(Map.of(
                "inputQueue", 3,
                "outputQueue", 7,
                "transporterAvailable", true
        ));

        mockMvc.perform(get("/api/queue/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.inputQueue").value(3))
                .andExpect(jsonPath("$.data.outputQueue").value(7));
    }
}
