package com.xa.mass.base.runtime.result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultCorrelationTest {

    @Test
    void workerLevelCorrelationIsActiveLeaseCorrelation() {
        TaskResultCorrelation correlation = TaskResultCorrelation.workerLevel(
                "task-1",
                "msg-1",
                "attempt-1",
                "lease-1",
                "worker-1",
                "batch-1"
        );

        assertTrue(correlation.activeLeasePresent());
        assertTrue(correlation.workerLevelLease());
    }

    @Test
    void noActiveLeaseCorrelationHasNoWorkerIdentity() {
        TaskResultCorrelation correlation = TaskResultCorrelation.noActiveLease("task-1", "msg-1");

        assertFalse(correlation.activeLeasePresent());
        assertFalse(correlation.workerLevelLease());
        assertTrue(correlation.workerId() == null);
    }
}
