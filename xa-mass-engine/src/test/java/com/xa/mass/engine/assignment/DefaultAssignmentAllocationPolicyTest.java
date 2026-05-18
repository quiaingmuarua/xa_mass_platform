package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.worker.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAssignmentAllocationPolicyTest {

    private final DefaultAssignmentAllocationPolicy policy = new DefaultAssignmentAllocationPolicy();

    @Test
    void taskLevelEventCapabilityUsesBaselineForMatchAndDesiredWorkersForDispatchLimit() {
        Task task = task(10, 3, 4, TaskStatus.READY);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 10, 20, 0, true));

        assertEquals(4, plan.desiredDispatchWorkerCount());
        assertEquals(4, plan.requiredStartWorkerCount());
        assertEquals(4, plan.requestedMatchCount());
        assertEquals(4, plan.dispatchCandidateLimit());
    }

    @Test
    void nonTaskLevelCapabilityExpandsMatchRequestToKnownCandidateCount() {
        Task task = task(3, 2, 1, TaskStatus.READY);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 3, 5, 0, false));

        assertEquals(2, plan.desiredDispatchWorkerCount());
        assertEquals(1, plan.requiredStartWorkerCount());
        assertEquals(5, plan.requestedMatchCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, plan.dispatchCandidateLimit());
    }

    @Test
    void defaultBulkBudgetIsObservableAndDoesNotLimitSmallPlans() {
        Task task = task(6, 2, 1, TaskStatus.RUNNING);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.RUNNING, 6, 4, 2, false));

        assertEquals(3, plan.rawDesiredDispatchWorkerCount());
        assertEquals(3, plan.desiredDispatchWorkerCount());
        assertEquals(4, plan.requestedMatchCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS - 2, plan.dispatchCandidateLimit());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, plan.workerBudget());
        assertEquals(2, plan.currentTaskWorkerCount());
        assertEquals(false, plan.budgetLimited());
    }

    @Test
    void defaultBulkBudgetCapsLargePlans() {
        Task task = task(100, 1, 1, TaskStatus.READY);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 100, 100, 0, false));

        assertEquals(100, plan.rawDesiredDispatchWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, plan.desiredDispatchWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, plan.requestedMatchCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, plan.workerBudget());
        assertEquals(true, plan.budgetLimited());
    }

    @Test
    void defaultInteractiveBudgetCapsLargePlansLowerThanBulk() {
        Task task = task(50, 1, 1, TaskStatus.READY);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 50, 50, 0, false));

        assertEquals(50, plan.rawDesiredDispatchWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS, plan.desiredDispatchWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS, plan.requestedMatchCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS, plan.workerBudget());
        assertEquals(true, plan.budgetLimited());
    }

    @Test
    void exhaustedBudgetProducesExplicitDecision() {
        Task task = task(10, 1, 1, TaskStatus.RUNNING);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task,
                TaskStatus.RUNNING,
                10,
                10,
                DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS,
                false));
        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.RUNNING, List.of());

        assertEquals(0, plan.desiredDispatchWorkerCount());
        assertEquals(0, plan.requestedMatchCount());
        assertEquals(AssignmentAllocationOutcome.BUDGET_EXHAUSTED, decision.outcome());
        assertTrue(decision.reason().contains("worker budget exhausted"));
    }

    @Test
    void finiteBudgetCapsMatchRequestAndDispatchLimitWithoutChangingMinimumGate() {
        DefaultAssignmentAllocationPolicy cappedPolicy = new DefaultAssignmentAllocationPolicy(
                (task, desiredDispatchWorkerCount, currentTaskWorkerCount) ->
                        new WorkerBudgetDecision(3, currentTaskWorkerCount, 1, true)
        );
        Task task = task(10, 2, 2, TaskStatus.READY);

        AssignmentAllocationPlan plan = cappedPolicy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 10, 10, 2, false));

        assertEquals(5, plan.rawDesiredDispatchWorkerCount());
        assertEquals(1, plan.desiredDispatchWorkerCount());
        assertEquals(2, plan.requiredStartWorkerCount());
        assertEquals(1, plan.requestedMatchCount());
        assertEquals(1, plan.dispatchCandidateLimit());
        assertEquals(3, plan.workerBudget());
        assertEquals(2, plan.currentTaskWorkerCount());
        assertEquals(true, plan.budgetLimited());
    }

    @Test
    void runningRefillRequiresOneWorkerRegardlessOfTaskMinimum() {
        Task task = task(10, 10, 5, TaskStatus.RUNNING);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.RUNNING, 1, 1, 0, false));

        assertEquals(1, plan.desiredDispatchWorkerCount());
        assertEquals(1, plan.requiredStartWorkerCount());
        assertEquals(1, plan.requestedMatchCount());
    }

    @Test
    void readyTaskBelowMinimumWorkerGateSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 2, TaskStatus.READY), TaskStatus.READY, 1, 1, 0, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.READY, List.of(matched("worker-1")));

        assertEquals(AssignmentAllocationOutcome.BELOW_MIN_START_GATE, decision.outcome());
        assertTrue(decision.dispatchCandidates().isEmpty());
    }

    @Test
    void statusChangeDuringMatchingSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 1, TaskStatus.READY), TaskStatus.READY, 1, 1, 0, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.PAUSED, List.of(matched("worker-1")));

        assertEquals(AssignmentAllocationOutcome.TASK_STATUS_CHANGED, decision.outcome());
        assertTrue(decision.reason().contains("READY to PAUSED"));
    }

    @Test
    void noMatchSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 1, TaskStatus.READY), TaskStatus.READY, 1, 0, 0, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.READY, List.of());

        assertEquals(AssignmentAllocationOutcome.NO_MATCH, decision.outcome());
    }

    @Test
    void dispatchDecisionTrimsCandidatesToPlanLimit() {
        Task task = task(5, 2, 1, TaskStatus.READY);
        AssignmentAllocationPlan plan = new AssignmentAllocationPlan(
                task, TaskStatus.READY, 5, 3, 3, 1, 3, 2, null, 0, false);

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.READY,
                List.of(matched("worker-1"), matched("worker-2"), matched("worker-3")));

        assertEquals(AssignmentAllocationOutcome.DISPATCH, decision.outcome());
        assertEquals(2, decision.dispatchCandidates().size());
        assertEquals("worker-1", decision.dispatchCandidates().get(0).getWorkerId());
        assertEquals("worker-2", decision.dispatchCandidates().get(1).getWorkerId());
    }

    private Task task(int targetNumber, int batchSize, int minWorkerCount, TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskTargetNumber(targetNumber);
        task.getExecutionSpec().setBatchSize(batchSize);
        task.setMinRequiredWorkerCount(minWorkerCount);
        task.setStatus(status);
        return task;
    }

    private WorkerSchedulingCandidate matched(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE, true, false)
        );
    }
}
