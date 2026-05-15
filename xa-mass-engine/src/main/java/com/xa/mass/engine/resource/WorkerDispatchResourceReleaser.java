package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.util.TraceEventLogger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Releases dispatch-time worker reservations and exclusive locks.
 */
public class WorkerDispatchResourceReleaser {
    private final WorkerManager workerManager;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final TraceEventLogger traceEventLogger;

    public WorkerDispatchResourceReleaser(WorkerManager workerManager,
                                          WorkerDispatchResourcePolicy resourcePolicy,
                                          TraceEventLogger traceEventLogger) {
        this.workerManager = workerManager;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
    }

    public void releaseReservations(Task task, Collection<WorkerSchedulingCandidate> candidates) {
        if (task == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (String workerId : distinctWorkerIds(candidates)) {
            workerManager.releaseWorkerReservation(workerId, task.getTid());
        }
    }

    public void releaseReservationsAndLocks(Task task,
                                            Collection<WorkerSchedulingCandidate> candidates,
                                            String trigger,
                                            String source,
                                            String reason) {
        if (task == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (String workerId : distinctWorkerIds(candidates)) {
            releaseReservationAndLock(task, workerId, trigger, source, reason);
        }
    }

    public void releaseLocks(Task task,
                             Collection<WorkerSchedulingCandidate> candidates,
                             String trigger,
                             String source,
                             String reason) {
        if (task == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (String workerId : distinctWorkerIds(candidates)) {
            releaseLockIfExclusive(task, workerId, trigger, source, reason);
        }
    }

    public void releaseReservationAndLock(Task task,
                                          String workerId,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || workerId == null || workerId.isBlank()) {
            return;
        }
        workerManager.releaseWorkerReservation(workerId, task.getTid());
        releaseLockIfExclusive(task, workerId, trigger, source, reason);
    }

    public void releaseLockIfExclusive(Task task,
                                       String workerId,
                                       String trigger,
                                       String source,
                                       String reason) {
        if (task == null || workerId == null || workerId.isBlank()) {
            return;
        }
        if (!resourcePolicy.usageForTask(task).exclusiveWorkerLock()) {
            return;
        }
        workerManager.unlockWorker(workerId);
        traceEventLogger.workerLockReleased(task.getTid(), workerId, trigger, source, reason);
    }

    public void releaseAttemptLockIfExclusive(Task task,
                                              String workerId,
                                              String workerContextId,
                                              String trigger,
                                              String source,
                                              String reason) {
        if (task == null || workerId == null || workerId.isBlank()) {
            return;
        }
        if (!resourcePolicy.usageForAttempt(task, workerContextId).exclusiveWorkerLock()) {
            return;
        }
        workerManager.unlockWorker(workerId);
        traceEventLogger.workerLockReleased(task.getTid(), workerId, trigger, source, reason);
    }

    private List<String> distinctWorkerIds(Collection<WorkerSchedulingCandidate> candidates) {
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(WorkerSchedulingCandidate::getWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }
}
