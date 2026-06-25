package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultCorrelationSupportTest {

    @Test
    void activeWorkerLevelLeaseBuildsWorkerLevelCorrelation() {
        TaskResultCorrelation correlation = TaskResultCorrelationSupport.fromRuntimeState(
                "task-1",
                "msg-1",
                null,
                lease()
        );

        assertTrue(correlation.activeLeasePresent());
        assertTrue(correlation.workerLevelLease());
        assertEquals("runtime-attempt-msg-1-1-worker-1-batch-1", correlation.projectedAttemptId());
    }

    @Test
    void missingLeaseBuildsNoActiveLeaseCorrelation() {
        TaskResultCorrelation correlation = TaskResultCorrelationSupport.fromRuntimeState(
                "task-1",
                "msg-1",
                null,
                null
        );

        assertFalse(correlation.activeLeasePresent());
        assertFalse(correlation.workerLevelLease());
        assertNull(correlation.workerId());
    }

    private ActiveLeaseRecord lease() {
        return new ActiveLeaseRecord(
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
    }
}
