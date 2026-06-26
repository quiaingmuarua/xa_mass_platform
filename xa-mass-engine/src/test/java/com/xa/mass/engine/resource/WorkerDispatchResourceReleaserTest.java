package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.selection.SelectedWorkerEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkerDispatchResourceReleaserTest {

    @Test
    void releaseReservationsAndLocksReleasesEachWorkerOnceAndEmitsWorkerResourceTrace() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        SelectedWorkerHandle selected = handle("worker-1", "task-1", true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            releaser.releaseReservationsAndLocks(
                    task,
                    List.of(selected, selected),
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

        verify(workerSelectionRuntime, org.mockito.Mockito.times(2)).releaseSelected(selected);
    }

    @Test
    void backgroundTaskReleasesReservationWithoutUnlock() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        task.getExecutionSpec().setForeground(false);
        SelectedWorkerHandle selected = handle("worker-1", "task-1", false);

        releaser.releaseReservationsAndLocks(
                task,
                List.of(selected),
                "UNLOCK_WORKER",
                "TestSource",
                "test release"
        );

        verify(workerSelectionRuntime).releaseSelected(selected);
        verify(workerSelectionRuntime, never()).releaseSelectedLock(selected);
    }

    @Test
    void releaseNonExclusiveReservationsSkipsExclusiveHandles() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        SelectedWorkerHandle exclusive = handle("worker-exclusive", "task-1", true);
        SelectedWorkerHandle nonExclusive = handle("worker-background", "task-1", false);

        releaser.releaseNonExclusiveReservations(task, List.of(exclusive, nonExclusive));

        verify(workerSelectionRuntime, never()).releaseSelected(exclusive);
        verify(workerSelectionRuntime).releaseSelected(nonExclusive);
        verify(workerSelectionRuntime, never()).releaseSelectedLock(nonExclusive);
    }

    @Test
    void releaseLocksUsesSelectedHandleLockFlagInsteadOfTaskPolicy() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        task.getExecutionSpec().setForeground(false);
        SelectedWorkerHandle selected = handle("worker-1", "task-1", true);

        releaser.releaseLocks(
                task,
                List.of(selected),
                "UNLOCK_WORKER",
                "TestSource",
                "test lock only"
        );

        verify(workerSelectionRuntime, never()).releaseSelected(selected);
        verify(workerSelectionRuntime).releaseSelectedLock(selected);
    }

    @Test
    void releaseLocksDoesNotReleaseReservations() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new DefaultWorkerDispatchResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");
        SelectedWorkerHandle selected = handle("worker-1", "task-1", true);

        releaser.releaseLocks(
                task,
                List.of(selected),
                "UNLOCK_WORKER",
                "TestSource",
                "test lock only"
        );

        verify(workerSelectionRuntime, never()).releaseSelected(selected);
        verify(workerSelectionRuntime).releaseSelectedLock(selected);
    }

    @Test
    void releaseAttemptLockUsesAttemptResourcePolicy() {
        WorkerSelectionRuntime workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        WorkerDispatchResourceReleaser releaser = new WorkerDispatchResourceReleaser(
                workerSelectionRuntime,
                new AttemptNonExclusiveResourcePolicy(),
                TraceEventLogger.noop()
        );
        Task task = task("task-1");

        releaser.releaseAttemptLockIfExclusive(
                task,
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", true),
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                "TestSource",
                "test attempt release"
        );

        verify(workerSelectionRuntime, never()).releaseSelectedLock(org.mockito.ArgumentMatchers.any(SelectedWorkerEvidence.class));
    }

    private Task task(String taskId) {
        Task task = new Task();
        task.setTid(taskId);
        return task;
    }

    private SelectedWorkerHandle handle(String workerId, String taskId, boolean exclusiveWorkerLock) {
        return SelectedWorkerHandle.of(
                workerId,
                "group-a",
                taskId,
                exclusiveWorkerLock
        );
    }

    private static final class AttemptNonExclusiveResourcePolicy implements WorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(true);
        }

        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
