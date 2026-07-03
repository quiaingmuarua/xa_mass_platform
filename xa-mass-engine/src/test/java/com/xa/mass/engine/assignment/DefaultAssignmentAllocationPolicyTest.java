package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultAssignmentAllocationPolicyTest {

    private final DefaultAssignmentAllocationPolicy policy = new DefaultAssignmentAllocationPolicy();

    @Test
    void firstRuntimeWorkerAllocationUsesMinStartGateWithoutReadyShellStatus() {
        Task task = task(TaskStatus.PAUSED, 2, 1);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task,
                task.getStatus(),
                1,
                0
        ));
        AssignmentAllocationDecision decision = policy.decide(
                plan,
                task.getStatus(),
                List.of(handle("worker-1", task.getTid()))
        );

        assertEquals(2, plan.requiredStartWorkerCount());
        assertEquals(AssignmentAllocationOutcome.BELOW_MIN_START_GATE, decision.outcome());
    }

    @Test
    void refillAllocationDoesNotApplyMinStartGateOrReadyRunningShellTruth() {
        Task task = task(TaskStatus.BLOCKED, 2, 1);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task,
                task.getStatus(),
                1,
                1
        ));
        AssignmentAllocationDecision decision = policy.decide(
                plan,
                task.getStatus(),
                List.of(handle("worker-1", task.getTid()))
        );

        assertEquals(1, plan.requiredStartWorkerCount());
        assertEquals(AssignmentAllocationOutcome.DISPATCH, decision.outcome());
    }

    @Test
    void terminalProjectionDuringSelectionStillStopsDispatch() {
        Task task = task(TaskStatus.READY, 1, 1);

        AssignmentAllocationPlan plan = policy.plan(new AssignmentAllocationRequest(
                task,
                task.getStatus(),
                1,
                0
        ));
        AssignmentAllocationDecision decision = policy.decide(
                plan,
                TaskStatus.TERMINAL,
                List.of(handle("worker-1", task.getTid()))
        );

        assertEquals(AssignmentAllocationOutcome.TASK_STATUS_CHANGED, decision.outcome());
    }

    private static Task task(TaskStatus status, int minRequiredWorkerCount, int batchSize) {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(status);
        task.setMinRequiredWorkerCount(minRequiredWorkerCount);
        TaskExecutionSpec executionSpec = new TaskExecutionSpec();
        executionSpec.setBatchSize(batchSize);
        task.setExecutionSpec(executionSpec);
        return task;
    }

    private static SelectedWorkerHandle handle(String workerId, String taskId) {
        return SelectedWorkerHandle.of(workerId, "group-a", taskId, true);
    }
}
