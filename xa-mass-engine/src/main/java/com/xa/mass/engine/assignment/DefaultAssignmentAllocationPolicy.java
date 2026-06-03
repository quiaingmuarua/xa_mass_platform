package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;

import java.util.List;
import java.util.Objects;

public final class DefaultAssignmentAllocationPolicy implements AssignmentAllocationPolicy {

    private final WorkerBudgetPolicy workerBudgetPolicy;
    private final SchedulingPlaneResolver schedulingPlaneResolver;

    public DefaultAssignmentAllocationPolicy() {
        this(new DefaultWorkerBudgetPolicy());
    }

    public DefaultAssignmentAllocationPolicy(WorkerBudgetPolicy workerBudgetPolicy) {
        this(workerBudgetPolicy, new DefaultSchedulingPlaneResolver());
    }

    DefaultAssignmentAllocationPolicy(WorkerBudgetPolicy workerBudgetPolicy,
                                      SchedulingPlaneResolver schedulingPlaneResolver) {
        this.workerBudgetPolicy = workerBudgetPolicy == null ? new DefaultWorkerBudgetPolicy() : workerBudgetPolicy;
        this.schedulingPlaneResolver = schedulingPlaneResolver == null
                ? new DefaultSchedulingPlaneResolver()
                : schedulingPlaneResolver;
    }

    @Override
    public AssignmentAllocationPlan plan(AssignmentAllocationRequest request) {
        Task task = request.task();
        ResolvedTaskSchedulingPolicy taskPolicy = resolveTaskSchedulingPolicy(task);
        int batchSize = Math.max(taskPolicy.batchSize(), 1);
        int rawDesiredDispatchWorkerCount = Math.max(1,
                (int) Math.ceil((double) Math.max(request.readyWorkCount(), 1) / batchSize));
        WorkerBudgetDecision budgetDecision = workerBudgetPolicy.resolve(
                taskPolicy,
                rawDesiredDispatchWorkerCount,
                request.currentTaskWorkerCount()
        );
        int desiredDispatchWorkerCount = budgetDecision.workerBudget() == null
                ? rawDesiredDispatchWorkerCount
                : Math.min(rawDesiredDispatchWorkerCount, budgetDecision.availableWorkerCount());
        int requiredStartWorkerCount = request.initialStatus() == TaskStatus.READY
                ? Math.max(taskPolicy.minRequiredWorkerCount(), 1)
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

    private ResolvedTaskSchedulingPolicy resolveTaskSchedulingPolicy(Task task) {
        return Objects.requireNonNull(schedulingPlaneResolver.resolve(task).taskSchedulingPolicy(),
                "taskSchedulingPolicy");
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
