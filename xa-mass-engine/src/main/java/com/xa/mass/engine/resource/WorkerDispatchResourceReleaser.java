package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.selection.SelectedWorkerEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;

import java.util.Collection;
import java.util.Objects;

/**
 * Releases dispatch-time worker reservations and exclusive locks.
 */
public final class WorkerDispatchResourceReleaser {
    private final WorkerSelectionRuntime workerSelectionRuntime;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final TraceEventLogger traceEventLogger;

    public WorkerDispatchResourceReleaser(WorkerSelectionRuntime workerSelectionRuntime,
                                          WorkerDispatchResourcePolicy resourcePolicy,
                                          TraceEventLogger traceEventLogger) {
        this.workerSelectionRuntime = Objects.requireNonNull(workerSelectionRuntime, "workerSelectionRuntime");
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
    }

    public void releaseReservations(Task task, Collection<SelectedWorkerHandle> handles) {
        if (task == null || handles == null || handles.isEmpty()) {
            return;
        }
        for (SelectedWorkerHandle handle : handles) {
            if (handle != null) {
                workerSelectionRuntime.releaseSelected(handle);
            }
        }
    }

    public void releaseReservationsAndLocks(Task task,
                                            Collection<SelectedWorkerHandle> handles,
                                            String trigger,
                                            String source,
                                            String reason) {
        if (task == null || handles == null || handles.isEmpty()) {
            return;
        }
        for (SelectedWorkerHandle handle : handles) {
            if (handle == null) {
                continue;
            }
            workerSelectionRuntime.releaseSelected(handle);
            emitLockReleasedIfExclusive(task, handle.workerId(), handle.exclusiveWorkerLock(), trigger, source, reason);
        }
    }

    public void releaseLocks(Task task,
                             Collection<SelectedWorkerHandle> handles,
                             String trigger,
                             String source,
                             String reason) {
        if (task == null || handles == null || handles.isEmpty()) {
            return;
        }
        for (SelectedWorkerHandle handle : handles) {
            if (handle == null) {
                continue;
            }
            if (handle.exclusiveWorkerLock()) {
                workerSelectionRuntime.releaseSelectedLock(handle);
            }
            emitLockReleasedIfExclusive(task, handle.workerId(), handle.exclusiveWorkerLock(), trigger, source, reason);
        }
    }

    public void releaseReservationAndLock(Task task,
                                          SelectedWorkerHandle handle,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || handle == null) {
            return;
        }
        String workerId = handle.workerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        workerSelectionRuntime.releaseSelected(handle);
        emitLockReleasedIfExclusive(task, workerId, handle.exclusiveWorkerLock(), trigger, source, reason);
    }

    private void emitLockReleasedIfExclusive(Task task,
                                             String workerId,
                                             boolean exclusiveWorkerLock,
                                             String trigger,
                                             String source,
                                             String reason) {
        if (task == null || workerId == null || workerId.isBlank() || !exclusiveWorkerLock) {
            return;
        }
        traceEventLogger.workerLockReleased(task.getTid(), workerId, trigger, source, reason);
        traceEventLogger.resourceReleased(
                task.getTid(),
                workerId,
                trigger,
                source,
                reason,
                "WORKER_LOCK"
        );
    }

    public void releaseAttemptLockIfExclusive(Task task,
                                              SelectedWorkerEvidence evidence,
                                              String trigger,
                                              String source,
                                              String reason) {
        if (task == null || evidence == null || evidence.workerId() == null || evidence.workerId().isBlank()) {
            return;
        }
        if (!resourcePolicy.usageForAttempt(task).exclusiveWorkerLock()) {
            return;
        }
        SelectedWorkerEvidence lockEvidence = new SelectedWorkerEvidence(
                evidence.workerId(),
                evidence.workerGroupId(),
                evidence.selectionScopeKey(),
                evidence.selectionToken(),
                evidence.scoreBandClaimScore(),
                true
        );
        workerSelectionRuntime.releaseSelectedLock(lockEvidence);
        emitLockReleasedIfExclusive(task, evidence.workerId(), true, trigger, source, reason);
    }
}
