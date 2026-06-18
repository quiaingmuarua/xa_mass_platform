package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWorkerDispatchResourcePolicyTest {

    private final DefaultWorkerDispatchResourcePolicy policy = new DefaultWorkerDispatchResourcePolicy();

    @Test
    void foregroundTaskUsesExclusiveWorkerLock() {
        Task task = new Task();

        WorkerDispatchResourceUsage usage = policy.usageForTask(task);

        assertTrue(usage.exclusiveWorkerLock());
    }

    @Test
    void backgroundTaskUsesSharedCapacityWithoutWorkerLock() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);

        WorkerDispatchResourceUsage usage = policy.usageForTask(task);

        assertFalse(usage.exclusiveWorkerLock());
    }

    @Test
    void attemptCleanupUsesTaskLevelBackgroundWorkerLockPolicy() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);

        WorkerDispatchResourceUsage usage = policy.usageForAttempt(task);

        assertFalse(usage.exclusiveWorkerLock());
    }

    @Test
    void nullTaskDefaultsToHistoricalExclusiveLock() {
        WorkerDispatchResourceUsage usage = policy.usageForTask(null);

        assertTrue(usage.exclusiveWorkerLock());
    }

}
