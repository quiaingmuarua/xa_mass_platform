package com.xa.mass.base.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkerDebugMessageStoreTest {

    @AfterEach
    void tearDown() {
        WorkerDebugMessageStore.clearAll();
    }

    @Test
    void markFailedUpdatesExistingOutboundRecord() {
        WorkerDebugMessageStore.recordOutbound(
                "worker-1",
                "demoApp",
                "mock.state.get",
                "CONTROL",
                "event",
                "msg-1",
                "{\"event\":\"mock.state.get\"}",
                "{\"msgId\":\"msg-1\"}",
                "queued"
        );

        WorkerDebugMessageStore.markFailed("msg-1", "endpoint unavailable");

        List<WorkerDebugMessageRecord> history = WorkerDebugMessageStore.getHistory("worker-1");
        assertEquals(1, history.size());
        assertEquals("FAILED", history.get(0).getStatus());
        assertEquals("endpoint unavailable", history.get(0).getDetail());
        assertEquals("mock.state.get", history.get(0).getEventCode());
    }

    @Test
    void inboundEventFallsBackToOutboundCapabilityIdentity() {
        WorkerDebugMessageStore.recordOutbound(
                "worker-1",
                "demoApp",
                "mock.state.get",
                "CONTROL",
                "event",
                "msg-1",
                "{\"event\":\"mock.state.get\"}",
                "{\"msgId\":\"msg-1\"}",
                "queued"
        );

        WorkerDebugMessageStore.recordInbound(
                "worker-1",
                "demoApp",
                null,
                "EVENT",
                "event",
                "msg-2",
                "msg-1",
                "{\"success\":true}",
                "{\"msgId\":\"msg-2\"}",
                "ack"
        );

        List<WorkerDebugMessageRecord> history = WorkerDebugMessageStore.getHistory("worker-1");
        assertEquals(2, history.size());
        assertEquals("mock.state.get", history.get(0).getEventCode());
        assertEquals("mock.state.get", history.get(1).getEventCode());
    }

    @Test
    void manualChatInboundKeepsNullEventIdentity() {
        WorkerDebugMessageStore.recordInbound(
                "worker-1",
                "demoApp",
                null,
                "EVENT",
                "manual-chat",
                "msg-3",
                null,
                "{\"message\":\"ok\"}",
                "{\"msgId\":\"msg-3\"}",
                "ack"
        );

        List<WorkerDebugMessageRecord> history = WorkerDebugMessageStore.getHistory("worker-1");
        assertEquals(1, history.size());
        assertNull(history.get(0).getEventCode());
    }
}
