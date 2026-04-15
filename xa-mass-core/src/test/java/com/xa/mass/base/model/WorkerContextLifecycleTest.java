package com.xa.mass.base.model;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerContextLifecycleTest {

    @Test
    void bindSendReleaseFollowsMainlineLifecycle() {
        WorkerContext wc = new WorkerContext("wc-1", "worker-1", "us");

        assertTrue(wc.bindToTask("task-1"));
        assertEquals(WorkerContextStatus.RESERVED, wc.getStatus());
        assertEquals("task-1", wc.getLastBindTaskId());

        assertTrue(wc.startOccupying());
        assertEquals(WorkerContextStatus.OCCUPIED, wc.getStatus());
        assertNotNull(wc.getLastUsedTime());

        assertTrue(wc.release());
        assertEquals(WorkerContextStatus.IDLE, wc.getStatus());
        assertNull(wc.getLastBindTaskId());
    }

    @Test
    void bindRejectsBlankTaskId() {
        WorkerContext wc = new WorkerContext("wc-2", "worker-2", "us");

        assertFalse(wc.bindToTask(" "));
        assertEquals(WorkerContextStatus.IDLE, wc.getStatus());
    }

    @Test
    void releaseDoesNotActAsBlockedRecovery() {
        WorkerContext wc = new WorkerContext("wc-3", "worker-3", "us");

        assertTrue(wc.block());
        assertFalse(wc.release());
        assertEquals(WorkerContextStatus.BLOCKED, wc.getStatus());

        assertTrue(wc.unblock());
        assertEquals(WorkerContextStatus.IDLE, wc.getStatus());
    }

    @Test
    void invalidateIsTerminalForWorkerContextStateMachine() {
        WorkerContext wc = new WorkerContext("wc-4", "worker-4", "us");

        assertTrue(wc.invalidate());
        assertEquals(WorkerContextStatus.INVALID, wc.getStatus());
        assertFalse(wc.unblock());
        assertFalse(wc.bindToTask("task-2"));
    }

    @Test
    void setStatusRejectsNull() {
        WorkerContext wc = new WorkerContext();

        assertThrows(NullPointerException.class, () -> wc.setStatus(null));
    }
}
