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

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private TransportOperations transportOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MessageController(transportOperations)).build();
    }

    @Test
    void sendMessageDelegatesToSdkTransportFacade() throws Exception {
        when(transportOperations.enqueueRawMessage(anyMap())).thenReturn(Map.of(
                "success", true,
                "msg", "message enqueued"
        ));

        mockMvc.perform(post("/api/message/send")
                        .contentType("application/json")
                        .content("""
                                {"hello":"world"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.msg").value("message enqueued"));
    }
}
