package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
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
        assertTrue(usage.statelessWorkerResource());
    }

    @Test
    void backgroundTaskUsesSharedCapacityWithoutWorkerLockForStatelessCandidate() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);

        WorkerDispatchResourceUsage usage = policy.usageForCandidate(task, candidate(null));

        assertFalse(usage.exclusiveWorkerLock());
        assertFalse(usage.legacyWorkerContextResource());
        assertTrue(usage.statelessWorkerResource());
    }

    @Test
    void candidateWithWorkerContextKeepsLegacyResourceLifecycleFlag() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerId("worker-1");
        workerContext.setWorkerContextId("ctx-1");

        WorkerDispatchResourceUsage usage = policy.usageForCandidate(task, candidate(workerContext));

        assertFalse(usage.exclusiveWorkerLock());
        assertTrue(usage.legacyWorkerContextResource());
        assertFalse(usage.statelessWorkerResource());
    }

    @Test
    void attemptUsageKeepsContextLifecycleAndTaskLockSemanticsSeparate() {
        Task task = new Task();
        task.getExecutionSpec().setForeground(false);

        WorkerDispatchResourceUsage usage = policy.usageForAttempt(task, "ctx-1");

        assertFalse(usage.exclusiveWorkerLock());
        assertTrue(usage.legacyWorkerContextResource());
    }

    @Test
    void nullTaskDefaultsToHistoricalExclusiveLock() {
        WorkerDispatchResourceUsage usage = policy.usageForTask(null);

        assertTrue(usage.exclusiveWorkerLock());
    }

    private WorkerSchedulingCandidate candidate(WorkerContext workerContext) {
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        if (workerContext != null) {
            workerContext.setWorkerId(worker.getWorkerId());
        }
        return new WorkerSchedulingCandidate(
                worker,
                workerContext,
                WorkerSchedulingView.from(worker, workerContext, WorkerReachabilityState.ONLINE, true, false)
        );
    }
}
