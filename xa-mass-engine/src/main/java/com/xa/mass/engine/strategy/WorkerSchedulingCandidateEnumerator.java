package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.engine.worker.WorkerGroupRecord;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.worker.WorkerSchedulingViewRuntime;

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

    List<WorkerSchedulingCandidate> enumerate(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }
        List<WorkerSchedulingCandidate> candidates = new ArrayList<>();
        for (Worker worker : workers) {
            candidates.add(toSchedulingCandidate(worker));
        }
        return candidates;
    }

    private WorkerSchedulingCandidate toSchedulingCandidate(Worker worker) {
        WorkerReachabilityState reachability = schedulingViewRuntime.getWorkerReachability(worker.getWorkerId());
        boolean dispatchEnabled = schedulingViewRuntime.isWorkerDispatchEnabled(worker);
        boolean workerLocked = schedulingViewRuntime.hasWorkerExclusiveLease(worker.getWorkerId());
        String workerGroupId = worker.getWorkerGroupId();
        WorkerGroupRecord workerGroup = workerGroupId == null || workerGroupId.isBlank()
                ? null
                : schedulingViewRuntime.workerGroupReadView(workerGroupId).orElse(null);
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(
                        worker,
                        workerGroup,
                        reachability,
                        dispatchEnabled,
                        workerLocked,
                        schedulingViewRuntime.getWorkerLoad(worker.getWorkerId())
                )
        );
    }
}
