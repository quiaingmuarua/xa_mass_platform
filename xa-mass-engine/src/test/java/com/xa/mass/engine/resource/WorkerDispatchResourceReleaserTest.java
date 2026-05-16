package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.util.TraceEventLogger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkerDispatchResourceReleaserTest {

    @Test
    void releaseReservationsAndLocksReleasesEachWorkerOnceAndEmitsWorkerResourceTrace() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerManager,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            releaser.releaseReservationsAndLocks(
                    task,
                    List.of(candidate("worker-1"), candidate("worker-1")),
                    "UNLOCK_WORKER",
                    "TestSource",
                    "test release"
            );

            capture.assertHasEvent("WORKER_LOCK_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && "TestSource".equals(mdc.get("source")));
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && "WORKER_LOCK".equals(mdc.get("resourceKind"))
                            && !mdc.containsKey("workerContextId")
                            && "TestSource".equals(mdc.get("source")));
        }

        verify(workerManager).releaseWorkerReservation("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void backgroundTaskReleasesReservationWithoutUnlock() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerManager,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        task.getExecutionSpec().setForeground(false);

        releaser.releaseReservationsAndLocks(
                task,
                List.of(candidate("worker-1")),
                "UNLOCK_WORKER",
                "TestSource",
                "test release"
        );

        verify(workerManager).releaseWorkerReservation("worker-1", "task-1");
        verify(workerManager, never()).unlockWorker("worker-1");
    }

    @Test
    void releaseLocksUsesCandidatePolicyInsteadOfTaskPolicy() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerManager,
                new CandidateExclusiveResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        task.getExecutionSpec().setForeground(false);

        releaser.releaseLocks(
                task,
                List.of(candidate("worker-1")),
                "UNLOCK_WORKER",
                "TestSource",
                "test lock only"
        );

        verify(workerManager, never()).releaseWorkerReservation("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void releaseLocksDoesNotReleaseReservations() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerManager,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");

        releaser.releaseLocks(
                task,
                List.of(candidate("worker-1")),
                "UNLOCK_WORKER",
                "TestSource",
                "test lock only"
        );

        verify(workerManager, never()).releaseWorkerReservation("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void releaseAttemptLockUsesAttemptResourcePolicy() {
        WorkerManager workerManager = mock(WorkerManager.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerManager,
                new AttemptNonExclusiveResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");

        releaser.releaseAttemptLockIfExclusive(
                task,
                "worker-1",
                "wctx-1",
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                "TestSource",
                "test attempt release"
        );

        verify(workerManager, never()).unlockWorker("worker-1");
    }

    private Task task(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        return task;
    }

    private WorkerSchedulingCandidate candidate(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE, true, false)
        );
    }

    private static final class CandidateExclusiveResourcePolicy extends DefaultWorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate) {
            return new WorkerDispatchResourceUsage(true);
        }
    }

    private static final class AttemptNonExclusiveResourcePolicy extends DefaultWorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task, String workerContextId) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
