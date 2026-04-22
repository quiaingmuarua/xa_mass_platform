package com.xa.mass.api.internal;

import com.google.gson.Gson;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.gateway.session.ServerSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {

    private final ServerSessionManager sessionManager = new ServerSessionManager();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DispatcherContextRegistry.register(new DispatcherContext(
                null,
                sessionManager,
                new GsonMessageCodec(new Gson())
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController()).build();
    }

    @AfterEach
    void tearDown() {
        DispatcherContextRegistry.register(null);
        sessionManager.shutdown();
    }

    @Test
    void listSessionsReturnsTransportNeutralEndpointSnapshots() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        sessionManager.addSession("worker-1", "task_messages", channel, mock(ChannelHandlerContext.class));

        mockMvc.perform(get("/api/session/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].workerId").value("worker-1"))
                .andExpect(jsonPath("$.data[0].connections[0].role").value("task_messages"))
                .andExpect(jsonPath("$.data[0].connections[0].active").value(true))
                .andExpect(jsonPath("$.data[0].connections[0].endpointId").exists())
                .andExpect(jsonPath("$.data[0].connections[0].transport").value("websocket"));
    }

    @Test
    void sessionStatsUseEndpointRegistryCounts() throws Exception {
        sessionManager.addSession("worker-1", "task_messages", new EmbeddedChannel(), mock(ChannelHandlerContext.class));
        sessionManager.addSession("worker-2", "task_messages", new EmbeddedChannel(), mock(ChannelHandlerContext.class));

        mockMvc.perform(get("/api/session/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.activeConnections").value(2))
                .andExpect(jsonPath("$.data.workerCount").value(2));
    }
}
