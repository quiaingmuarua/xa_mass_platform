package com.xa.mass.testing.perf;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;

import java.util.Locale;

final class PerfWorkerMatchingSupport {

    private static final WorkerDispatchResourcePolicy RESOURCE_POLICY = new DefaultWorkerDispatchResourcePolicy();

    private PerfWorkerMatchingSupport() {
    }

    static WorkerSchedulingCandidate tryReserveCandidate(WorkerAdmissionRuntime admissionRuntime,
                                                         WorkerSchedulingViewRuntime schedulingViewRuntime,
                                                         Task task,
                                                         WorkerResourceRecord worker) {
        if (admissionRuntime == null || schedulingViewRuntime == null || task == null || worker == null
                || worker.workerId() == null || worker.workerId().isBlank()
                || worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return null;
        }
        String workerId = worker.workerId();
        WorkerAdmissionTarget admissionTarget =
                WorkerAdmissionTarget.groupScoped(worker.workerGroupId(), workerId, task.getTid());
        WorkerAdmissionResult reserveResult = admissionRuntime.reserveWorkerCapacity(admissionTarget);
        if (!reserveResult.accepted()) {
            return null;
        }
        WorkerSchedulingCandidate candidate = candidate(schedulingViewRuntime, worker);
        if (RESOURCE_POLICY.usageForCandidate(task, candidate).exclusiveWorkerLock()
                && !admissionRuntime.tryAcquireWorkerExclusiveLease(workerId)) {
            admissionRuntime.releaseWorkerReservation(admissionTarget);
            return null;
        }
        return candidate(schedulingViewRuntime, worker);
    }

    static boolean workerAvailable(WorkerResourceRecord worker) {
        if (worker == null || worker.statusName() == null || worker.statusName().isBlank()) {
            return false;
        }
        try {
            return WorkerStatus.valueOf(worker.statusName().trim().toUpperCase(Locale.ROOT)).isAvailable();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean supportsProject(WorkerResourceRecord worker, String projectCode) {
        return worker != null
                && worker.supportedProjects() != null
                && worker.supportedProjects().contains(projectCode);
    }

    static boolean supportsProject(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                   WorkerResourceRecord worker,
                                   String projectCode) {
        if (projectCode == null || projectCode.isBlank()) {
            return false;
        }
        if (supportsProject(worker, projectCode)) {
            return true;
        }
        if (schedulingViewRuntime == null || worker == null || worker.workerGroupId() == null
                || worker.workerGroupId().isBlank()) {
            return false;
        }
        return schedulingViewRuntime.workerGroupReadView(worker.workerGroupId())
                .map(view -> view.projectCodes().contains(projectCode))
                .orElse(false);
    }

    private static WorkerSchedulingCandidate candidate(WorkerSchedulingViewRuntime schedulingViewRuntime,
                                                       WorkerResourceRecord worker) {
        String workerId = worker.workerId();
        WorkerCandidateRow candidateRow = candidateRow(worker);
        WorkerReachabilityState reachability = schedulingViewRuntime.getWorkerReachability(workerId);
        return new WorkerSchedulingCandidate(
                candidateRow,
                WorkerSchedulingView.from(
                        candidateRow,
                        schedulingViewRuntime.workerGroupReadView(worker.workerGroupId()).orElse(null),
                        reachability == WorkerReachabilityState.UNKNOWN
                                ? WorkerReachabilityState.ONLINE
                                : reachability,
                        schedulingViewRuntime.isWorkerDispatchEnabled(workerId),
                        schedulingViewRuntime.hasWorkerExclusiveLease(workerId),
                        schedulingViewRuntime.getWorkerLoad(workerId)
                )
        );
    }

    private static WorkerCandidateRow candidateRow(WorkerResourceRecord worker) {
        return new WorkerCandidateRow(
                worker.workerId(),
                worker.statusName(),
                worker.agentVersion(),
                worker.lastHeartbeat(),
                worker.supportedProjects(),
                worker.supportedEventCodes(),
                worker.workerGroupId(),
                worker.adapterNodeId(),
                worker.adapterId(),
                worker.onlineStrategy(),
                worker.maxConcurrentWork(),
                worker.attributes(),
                worker.createTime(),
                worker.updateTime(),
                workerAvailable(worker)
        );
    }
}
