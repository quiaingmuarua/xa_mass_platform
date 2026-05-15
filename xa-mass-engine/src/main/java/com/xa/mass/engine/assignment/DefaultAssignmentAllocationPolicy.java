package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.MatchedWorkerContext;

import java.util.List;

public final class DefaultAssignmentAllocationPolicy implements AssignmentAllocationPolicy {

    @Override
    public AssignmentAllocationPlan plan(AssignmentAllocationRequest request) {
        Task task = request.task();
        int batchSize = Math.max(task.getExecutionSpec().getBatchSize(), 1);
        int desiredDispatchWorkerCount = Math.max(1,
                (int) Math.ceil((double) Math.max(request.readyWorkCount(), 1) / batchSize));
        int requiredStartWorkerCount = request.initialStatus() == TaskStatus.READY
                ? Math.max(task.getMinRequiredWorkerCount(), 1)
                : 1;
        int baseline = Math.max(requiredStartWorkerCount, desiredDispatchWorkerCount);
        int requestedMatchCount = request.taskLevelEventCapability()
                ? baseline
                : Math.max(baseline, Math.max(request.workerCandidateCount(), 1));
        int dispatchCandidateLimit = request.taskLevelEventCapability()
                ? desiredDispatchWorkerCount
                : Integer.MAX_VALUE;
        return new AssignmentAllocationPlan(
                task,
                request.initialStatus(),
                request.readyWorkCount(),
                desiredDispatchWorkerCount,
                requiredStartWorkerCount,
                requestedMatchCount,
                dispatchCandidateLimit
        );
    }

    @Override
    public AssignmentAllocationDecision decide(AssignmentAllocationPlan plan,
                                               TaskStatus currentStatus,
                                               List<MatchedWorkerContext> matchedWorkers) {
        List<MatchedWorkerContext> matched = matchedWorkers == null ? List.of() : matchedWorkers;
        if (matched.isEmpty()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.NO_MATCH,
                    List.of(),
                    "no matched worker-context candidates"
            );
        }
        if (plan.initialStatus() == TaskStatus.READY && matched.size() < plan.requiredStartWorkerCount()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.BELOW_MIN_START_GATE,
                    List.of(),
                    "matched workers below minimum start gate"
            );
        }
        if (currentStatus != plan.initialStatus()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.TASK_STATUS_CHANGED,
                    List.of(),
                    "task status changed during matching from " + plan.initialStatus() + " to " + currentStatus
            );
        }

        int dispatchCandidateCount = Math.min(matched.size(), plan.dispatchCandidateLimit());
        List<MatchedWorkerContext> dispatchCandidates = matched.subList(0, dispatchCandidateCount);
        if (dispatchCandidates.isEmpty()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.NO_DISPATCH_CANDIDATES,
                    List.of(),
                    "no dispatch candidates remained after capacity trim"
            );
        }
        return new AssignmentAllocationDecision(
                AssignmentAllocationOutcome.DISPATCH,
                dispatchCandidates,
                "matched workers dispatched"
        );
    }
}
