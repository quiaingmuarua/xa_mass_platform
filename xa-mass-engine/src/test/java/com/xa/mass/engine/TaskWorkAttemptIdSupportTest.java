package com.xa.mass.engine;

import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskWorkAttemptIdSupportTest {

    @Test
    void workerLevelAttemptIdUsesWorkerAndBatchIdentity() {
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                "msg-1",
                1,
                "worker-1",
                "batch-1"
        );

        assertEquals("runtime-attempt-msg-1-1-worker-1-batch-1", attemptId);
    }

    @Test
    void activeLeaseUsesWorkerLevelAttemptId() {
        ActiveLeaseRepairCandidate lease = new ActiveLeaseRepairCandidate(
                "task-1",
                "msg-1",
                "lease-1",
                "worker-1",
                "",
                "batch-1",
                "",
                null,
                1,
                System.currentTimeMillis() + 30_000L
        );

        assertEquals(
                "runtime-attempt-msg-1-1-worker-1-batch-1",
                TaskWorkAttemptIdSupport.runtimeAttemptId("msg-1", 1, lease)
        );
    }

}
