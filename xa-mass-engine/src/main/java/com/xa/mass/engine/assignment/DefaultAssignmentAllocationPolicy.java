package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

import java.util.List;

public final class DefaultAssignmentAllocationPolicy implements AssignmentAllocationPolicy {

    private final WorkerBudgetPolicy workerBudgetPolicy;

    public DefaultAssignmentAllocationPolicy() {
        this(new DefaultWorkerBudgetPolicy());
    }

    public DefaultAssignmentAllocationPolicy(WorkerBudgetPolicy workerBudgetPolicy) {
        this.workerBudgetPolicy = workerBudgetPolicy == null ? new DefaultWorkerBudgetPolicy() : workerBudgetPolicy;
    }

    @Override
    public AssignmentAllocationPlan plan(AssignmentAllocationRequest request) {
        Task task = request.task();
        int batchSize = Math.max(task.getExecutionSpec().getBatchSize(), 1);
        int rawDesiredDispatchWorkerCount = Math.max(1,
                (int) Math.ceil((double) Math.max(request.readyWorkCount(), 1) / batchSize));
        WorkerBudgetDecision budgetDecision = workerBudgetPolicy.resolve(
                task,
                rawDesiredDispatchWorkerCount,
                request.currentTaskWorkerCount()
        );
        int desiredDispatchWorkerCount = budgetDecision.workerBudget() == null
                ? rawDesiredDispatchWorkerCount
                : Math.min(rawDesiredDispatchWorkerCount, budgetDecision.availableWorkerCount());
        int requiredStartWorkerCount = request.initialStatus() == TaskStatus.READY
                ? Math.max(task.getMinRequiredWorkerCount(), 1)
                : 1;
        int baseline = Math.max(requiredStartWorkerCount, desiredDispatchWorkerCount);
        int requestedMatchCount = baseline;
        int dispatchCandidateLimit = desiredDispatchWorkerCount;
        if (budgetDecision.workerBudget() != null) {
            requestedMatchCount = Math.min(requestedMatchCount, budgetDecision.availableWorkerCount());
            dispatchCandidateLimit = Math.min(dispatchCandidateLimit, budgetDecision.availableWorkerCount());
        }
        return new AssignmentAllocationPlan(
                task,
                request.initialStatus(),
                request.readyWorkCount(),
                rawDesiredDispatchWorkerCount,
                desiredDispatchWorkerCount,
                requiredStartWorkerCount,
                requestedMatchCount,
                dispatchCandidateLimit,
                budgetDecision.workerBudget(),
                budgetDecision.currentTaskWorkerCount(),
                budgetDecision.budgetLimited()
        );
    }

    @Override
    public AssignmentAllocationDecision decide(AssignmentAllocationPlan plan,
                                               TaskStatus currentStatus,
                                               List<WorkerSchedulingCandidate> matchedWorkers) {
        List<WorkerSchedulingCandidate> matched = matchedWorkers == null ? List.of() : matchedWorkers;
        if (plan.workerBudget() != null && plan.workerBudget() <= plan.currentTaskWorkerCount()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.BUDGET_EXHAUSTED,
                    List.of(),
                    "worker budget exhausted for task"
            );
        }
        if (matched.isEmpty()) {
            return new AssignmentAllocationDecision(
                    AssignmentAllocationOutcome.NO_MATCH,
                    List.of(),
                    "no matched worker scheduling candidates"
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
        List<WorkerSchedulingCandidate> dispatchCandidates = matched.subList(0, dispatchCandidateCount);
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
