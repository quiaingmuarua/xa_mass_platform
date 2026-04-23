package com.xa.mass.base.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    }
}
