package com.xa.mass.engine;

import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskWorkAttemptIdSupportTest {

    @Test
    void workerLevelAttemptIdDoesNotIncludeLegacyWorkerContextPlaceholder() {
        String attemptId = TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                "msg-1",
                1,
                "worker-1",
                "batch-1"
        );

        assertEquals("runtime-attempt-msg-1-1-worker-1-batch-1", attemptId);
    }

    @Test
    void activeLeaseWithoutWorkerContextUsesWorkerLevelAttemptId() {
        ActiveLeaseRecord lease = new ActiveLeaseRecord(
                "task-1",
                "msg-1",
                "lease-1",
                "worker-1",
                null,
                "batch-1",
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

    @Test
    void activeLeaseWithWorkerContextKeepsLegacyAttemptIdShape() {
        ActiveLeaseRecord lease = new ActiveLeaseRecord(
                "task-1",
                "msg-1",
                "lease-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                null,
                0,
                Instant.now(),
                Instant.now()
        );

        assertEquals(
                "runtime-attempt-msg-1-1-worker-1-ctx-1-batch-1",
                TaskWorkAttemptIdSupport.runtimeAttemptId("msg-1", 1, lease)
        );
    }
}
