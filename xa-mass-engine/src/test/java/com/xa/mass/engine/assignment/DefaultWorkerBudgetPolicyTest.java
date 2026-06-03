package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DefaultWorkerBudgetPolicyTest {

    private final DefaultWorkerBudgetPolicy policy = new DefaultWorkerBudgetPolicy();

    @Test
    void interactiveWorkloadUsesSmallDefaultBudget() {
        WorkerBudgetDecision decision = policy.resolve(taskPolicy(TaskWorkloadClass.INTERACTIVE), 3, 1);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS, decision.workerBudget());
        assertEquals(1, decision.currentTaskWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS - 1, decision.availableWorkerCount());
        assertFalse(decision.budgetLimited());
    }

    @Test
    void bulkAndUnknownWorkloadUseBulkDefaultBudget() {
        WorkerBudgetDecision bulk = policy.resolve(taskPolicy(TaskWorkloadClass.BULK), 30, 0);
        WorkerBudgetDecision unknown = policy.resolve(taskPolicy(null), 30, 0);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, bulk.workerBudget());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, unknown.workerBudget());
        assertTrue(bulk.budgetLimited());
        assertTrue(unknown.budgetLimited());
    }

    @Test
    void currentTaskWorkerCountReducesAvailableBudget() {
        WorkerBudgetDecision decision = policy.resolve(taskPolicy(TaskWorkloadClass.BULK), 5, 19);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, decision.workerBudget());
        assertEquals(19, decision.currentTaskWorkerCount());
        assertEquals(1, decision.availableWorkerCount());
        assertTrue(decision.budgetLimited());
    }

    @Test
    void exhaustedBudgetHasNoAvailableWorkers() {
        WorkerBudgetDecision decision = policy.resolve(taskPolicy(TaskWorkloadClass.INTERACTIVE), 1, 5);

        assertEquals(0, decision.availableWorkerCount());
        assertTrue(decision.budgetLimited());
    }

    private ResolvedTaskSchedulingPolicy taskPolicy(TaskWorkloadClass workloadClass) {
        return new ResolvedTaskSchedulingPolicy(
                "task-1",
                workloadClass,
                null,
                null,
                null,
                null,
                null,
                1,
                0,
                0
        );
    }
}
