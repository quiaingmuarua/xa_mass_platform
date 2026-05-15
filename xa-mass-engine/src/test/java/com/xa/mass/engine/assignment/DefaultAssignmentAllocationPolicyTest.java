package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;
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
                task, TaskStatus.READY, 10, 20, true));

        assertEquals(4, plan.desiredDispatchWorkerCount());
        assertEquals(4, plan.requiredStartWorkerCount());
        assertEquals(4, plan.requestedMatchCount());
        assertEquals(4, plan.dispatchCandidateLimit());
    }

    @Test
    void nonTaskLevelCapabilityExpandsMatchRequestToKnownCandidateCount() {
        Task task = task(3, 2, 1, TaskStatus.READY);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.READY, 3, 5, false));

        assertEquals(2, plan.desiredDispatchWorkerCount());
        assertEquals(1, plan.requiredStartWorkerCount());
        assertEquals(5, plan.requestedMatchCount());
        assertEquals(Integer.MAX_VALUE, plan.dispatchCandidateLimit());
    }

    @Test
    void runningRefillRequiresOneWorkerRegardlessOfTaskMinimum() {
        Task task = task(10, 10, 5, TaskStatus.RUNNING);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task, TaskStatus.RUNNING, 1, 1, false));

        assertEquals(1, plan.desiredDispatchWorkerCount());
        assertEquals(1, plan.requiredStartWorkerCount());
        assertEquals(1, plan.requestedMatchCount());
    }

    @Test
    void readyTaskBelowMinimumWorkerGateSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 2, TaskStatus.READY), TaskStatus.READY, 1, 1, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.READY, List.of(matched("worker-1")));

        assertEquals(AssignmentAllocationOutcome.BELOW_MIN_START_GATE, decision.outcome());
        assertTrue(decision.dispatchCandidates().isEmpty());
    }

    @Test
    void statusChangeDuringMatchingSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 1, TaskStatus.READY), TaskStatus.READY, 1, 1, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.PAUSED, List.of(matched("worker-1")));

        assertEquals(AssignmentAllocationOutcome.TASK_STATUS_CHANGED, decision.outcome());
        assertTrue(decision.reason().contains("READY to PAUSED"));
    }

    @Test
    void noMatchSkipsDispatch() {
        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task(1, 1, 1, TaskStatus.READY), TaskStatus.READY, 1, 0, false));

        AssignmentAllocationDecision decision = policy.decide(plan, TaskStatus.READY, List.of());

        assertEquals(AssignmentAllocationOutcome.NO_MATCH, decision.outcome());
    }

    @Test
    void dispatchDecisionTrimsCandidatesToPlanLimit() {
        Task task = task(5, 2, 1, TaskStatus.READY);
        AssignmentAllocationPlan plan = new AssignmentAllocationPlan(
                task, TaskStatus.READY, 5, 3, 1, 3, 2);

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
        WorkerContext context = new WorkerContext();
        context.setWorkerId(workerId);
        context.setWorkerContextId("ctx-" + workerId);
        return new WorkerSchedulingCandidate(
                worker,
                context,
                WorkerSchedulingView.from(worker, context, WorkerReachabilityState.ONLINE, true, false)
        );
    }
}
