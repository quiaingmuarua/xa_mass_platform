package com.xa.mass.engine.strategy;

import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerGroupCapabilityView;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerSchedulingViewRuntime;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owner for turning worker read-model rows into scheduling candidates.
 */
final class WorkerSchedulingCandidateEnumerator {

    private final WorkerSchedulingViewRuntime schedulingViewRuntime;

    WorkerSchedulingCandidateEnumerator(WorkerSchedulingViewRuntime schedulingViewRuntime) {
        this.schedulingViewRuntime = Objects.requireNonNull(schedulingViewRuntime, "schedulingViewRuntime");
    }

    List<WorkerSchedulingCandidate> enumerate(List<WorkerCandidateRow> candidateRows) {
        if (candidateRows == null || candidateRows.isEmpty()) {
            return List.of();
        }
        List<WorkerSchedulingCandidate> candidates = new ArrayList<>();
        for (WorkerCandidateRow candidateRow : candidateRows) {
            candidates.add(toSchedulingCandidate(candidateRow));
        }
        return candidates;
    }

    private WorkerSchedulingCandidate toSchedulingCandidate(WorkerCandidateRow candidateRow) {
        WorkerReachabilityState reachability = schedulingViewRuntime.getWorkerReachability(candidateRow.workerId());
        boolean dispatchEnabled = schedulingViewRuntime.isWorkerDispatchEnabled(candidateRow.workerId());
        boolean workerLocked = schedulingViewRuntime.hasWorkerExclusiveLease(candidateRow.workerId());
        String workerGroupId = candidateRow.workerGroupId();
        WorkerGroupCapabilityView workerGroup = workerGroupId == null || workerGroupId.isBlank()
                ? null
                : schedulingViewRuntime.workerGroupReadView(workerGroupId).orElse(null);
        return new WorkerSchedulingCandidate(
                candidateRow,
                WorkerSchedulingView.from(
                        candidateRow,
                        workerGroup,
                        reachability,
                        dispatchEnabled,
                        workerLocked,
                        schedulingViewRuntime.getWorkerLoad(candidateRow.workerId())
                )
        );
    }
}
