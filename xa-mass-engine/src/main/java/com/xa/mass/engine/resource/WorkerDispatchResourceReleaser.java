package com.xa.mass.engine.resource;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.worker.WorkerAdmissionRuntime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Releases dispatch-time worker reservations and exclusive locks.
 */
public final class WorkerDispatchResourceReleaser {
    private final WorkerAdmissionRuntime workerAdmissionRuntime;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final TraceEventLogger traceEventLogger;

    public WorkerDispatchResourceReleaser(WorkerAdmissionRuntime workerAdmissionRuntime,
                                          WorkerDispatchResourcePolicy resourcePolicy,
                                          TraceEventLogger traceEventLogger) {
        this.workerAdmissionRuntime = workerAdmissionRuntime;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.traceEventLogger = traceEventLogger == null ? TraceEventLogger.noop() : traceEventLogger;
    }

    public void releaseReservations(Task task, Collection<WorkerSchedulingCandidate> candidates) {
        if (task == null || candidates == null || candidates.isEmpty()) {
            return;
        }
        for (String workerId : distinctWorkerIds(candidates)) {
            workerAdmissionRuntime.releaseWorkerReservation(workerId, task.getTid());
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
        for (WorkerCleanupDecision decision : cleanupDecisions(task, candidates)) {
            workerAdmissionRuntime.releaseWorkerReservation(decision.workerId(), task.getTid());
            releaseLockIfExclusive(task, decision.workerId(), decision.exclusiveWorkerLock(),
                    trigger, source, reason);
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
        for (WorkerCleanupDecision decision : cleanupDecisions(task, candidates)) {
            releaseLockIfExclusive(task, decision.workerId(), decision.exclusiveWorkerLock(),
                    trigger, source, reason);
        }
    }

    public void releaseReservationAndLock(Task task,
                                          WorkerSchedulingCandidate candidate,
                                          String trigger,
                                          String source,
                                          String reason) {
        if (task == null || candidate == null) {
            return;
        }
        String workerId = candidate.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        workerAdmissionRuntime.releaseWorkerReservation(workerId, task.getTid());
        releaseLockIfExclusive(task, workerId,
                resourcePolicy.usageForCandidate(task, candidate).exclusiveWorkerLock(),
                trigger, source, reason);
    }

    private void releaseLockIfExclusive(Task task,
                                        String workerId,
                                        boolean exclusiveWorkerLock,
                                        String trigger,
                                        String source,
                                        String reason) {
        if (task == null || workerId == null || workerId.isBlank() || !exclusiveWorkerLock) {
            return;
        }
        releaseWorkerExclusiveLease(task, workerId, trigger, source, reason);
    }

    private void releaseWorkerExclusiveLease(Task task,
                              String workerId,
                              String trigger,
                              String source,
                              String reason) {
        workerAdmissionRuntime.releaseWorkerExclusiveLease(workerId);
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
                                              String workerId,
                                              String trigger,
                                              String source,
                                              String reason) {
        if (task == null || workerId == null || workerId.isBlank()) {
            return;
        }
        if (!resourcePolicy.usageForAttempt(task).exclusiveWorkerLock()) {
            return;
        }
        releaseWorkerExclusiveLease(task, workerId, trigger, source, reason);
    }

    private List<String> distinctWorkerIds(Collection<WorkerSchedulingCandidate> candidates) {
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(WorkerSchedulingCandidate::getWorkerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<WorkerCleanupDecision> cleanupDecisions(Task task,
                                                         Collection<WorkerSchedulingCandidate> candidates) {
        Map<String, Boolean> exclusiveLockByWorkerId = new LinkedHashMap<>();
        candidates.stream()
                .filter(Objects::nonNull)
                .forEach(candidate -> {
                    String workerId = candidate.getWorkerId();
                    if (workerId == null || workerId.isBlank()) {
                        return;
                    }
                    boolean exclusiveWorkerLock =
                            resourcePolicy.usageForCandidate(task, candidate).exclusiveWorkerLock();
                    exclusiveLockByWorkerId.merge(workerId, exclusiveWorkerLock, Boolean::logicalOr);
                });
        return exclusiveLockByWorkerId.entrySet().stream()
                .map(entry -> new WorkerCleanupDecision(entry.getKey(), entry.getValue()))
                .toList();
    }

    private record WorkerCleanupDecision(String workerId, boolean exclusiveWorkerLock) {
    }
}
