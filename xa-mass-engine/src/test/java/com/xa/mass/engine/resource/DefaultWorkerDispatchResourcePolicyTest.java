package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
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
    void backgroundTaskUsesSharedCapacityWithoutWorkerLockForStatelessCandidate() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);

        WorkerDispatchResourceUsage usage = policy.usageForCandidate(task, candidate());

        assertFalse(usage.exclusiveWorkerLock());
    }

    @Test
    void attemptWorkerContextIdentityDoesNotChangeBackgroundWorkerLockPolicy() {
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

    private WorkerSchedulingCandidate candidate() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE, true, false)
        );
    }
}
