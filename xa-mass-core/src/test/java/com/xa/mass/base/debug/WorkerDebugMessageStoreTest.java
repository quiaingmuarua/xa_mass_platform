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
                "msg-1",
                "{\"eventCode\":\"mock.state.get\"}",
                "{\"messageId\":\"msg-1\"}",
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
                "msg-1",
                "{\"eventCode\":\"mock.state.get\"}",
                "{\"messageId\":\"msg-1\"}",
                "queued"
        );

        WorkerDebugMessageStore.recordInbound(
                "worker-1",
                "demoApp",
                null,
                "msg-2",
                "msg-1",
                "{\"success\":true}",
                "{\"messageId\":\"msg-2\"}",
                "ack"
        );

        List<WorkerDebugMessageRecord> history = WorkerDebugMessageStore.getHistory("worker-1");
        assertEquals(2, history.size());
        assertEquals("mock.state.get", history.get(0).getEventCode());
        assertEquals("mock.state.get", history.get(1).getEventCode());
    }

    @Test
    void inboundWithoutCorrelationKeepsNullEventIdentity() {
        WorkerDebugMessageStore.recordInbound(
                "worker-1",
                "demoApp",
                null,
                "msg-3",
                null,
                "{\"message\":\"ok\"}",
                "{\"messageId\":\"msg-3\"}",
                "ack"
        );

        List<WorkerDebugMessageRecord> history = WorkerDebugMessageStore.getHistory("worker-1");
        assertEquals(1, history.size());
        assertNull(history.get(0).getEventCode());
    }
}
