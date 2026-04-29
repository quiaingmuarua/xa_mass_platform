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

    @Test
    void queueDetailIncludesRuntimeDeliveryStats() throws Exception {
        when(transportOperations.getQueueDetail()).thenReturn(Map.of(
                "inputQueue", -1,
                "outputQueue", -1,
                "inputQueueSize", -1,
                "outputQueueSize", -1,
                "transporterAvailable", false,
                "deliveryQueue", Map.of(
                        "available", true,
                        "queuedItems", 4,
                        "queueCount", 2,
                        "waitingPollers", 1,
                        "maxQueuedItems", 100_000,
                        "queueByAdapter", Map.of(
                                "polling", Map.of(
                                        "queuedItems", 4,
                                        "queueCount", 2,
                                        "waitingPollers", 1,
                                        "oldestQueuedAgeMillis", 25L,
                                        "backpressureRejectedItems", 3L
                                )
                        ),
                        "directByAdapter", Map.of(
                                "websocket", Map.of(
                                        "sentItems", 7L,
                                        "offlineItems", 1L,
                                        "failedItems", 0L,
                                        "invalidItems", 0L,
                                        "unavailableItems", 0L
                                )
                        )
                ),
                "runtimeExecutors", Map.of(
                        "transport", Map.of(
                                "available", true,
                                "submittedTasks", 8,
                                "completedTasks", 7,
                                "rejectedTasks", 1,
                                "activeTasks", 1,
                                "pendingTasks", 1,
                                "maxPendingTasks", 10_000
                        ),
                        "event", Map.of(
                                "available", false,
                                "submittedTasks", 0,
                                "completedTasks", 0,
                                "rejectedTasks", 0,
                                "activeTasks", 0,
                                "pendingTasks", 0,
                                "maxPendingTasks", 0
                        )
                )
        ));

        mockMvc.perform(get("/api/queue/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.inputQueue").value(-1))
                .andExpect(jsonPath("$.data.inputQueueSize").value(-1))
                .andExpect(jsonPath("$.data.outputQueue").value(-1))
                .andExpect(jsonPath("$.data.outputQueueSize").value(-1))
                .andExpect(jsonPath("$.data.deliveryQueue.available").value(true))
                .andExpect(jsonPath("$.data.deliveryQueue.queuedItems").value(4))
                .andExpect(jsonPath("$.data.deliveryQueue.queueCount").value(2))
                .andExpect(jsonPath("$.data.deliveryQueue.waitingPollers").value(1))
                .andExpect(jsonPath("$.data.deliveryQueue.maxQueuedItems").value(100000))
                .andExpect(jsonPath("$.data.deliveryQueue.queueByAdapter.polling.queuedItems").value(4))
                .andExpect(jsonPath("$.data.deliveryQueue.queueByAdapter.polling.backpressureRejectedItems").value(3))
                .andExpect(jsonPath("$.data.deliveryQueue.directByAdapter.websocket.sentItems").value(7))
                .andExpect(jsonPath("$.data.deliveryQueue.directByAdapter.websocket.offlineItems").value(1))
                .andExpect(jsonPath("$.data.runtimeExecutors.transport.available").value(true))
                .andExpect(jsonPath("$.data.runtimeExecutors.transport.rejectedTasks").value(1))
                .andExpect(jsonPath("$.data.runtimeExecutors.event.available").value(false));
    }
}
