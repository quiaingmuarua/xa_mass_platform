package com.xa.mass.engine;

import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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
        ActiveLeaseRecord lease = new ActiveLeaseRecord(
                "task-1",
                "msg-1",
                "lease-1",
                "worker-1",
                null,
                "batch-1",
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now()
        );

        assertEquals(
                "runtime-attempt-msg-1-1-worker-1-batch-1",
                TaskWorkAttemptIdSupport.runtimeAttemptId("msg-1", 1, lease)
        );
    }

}
