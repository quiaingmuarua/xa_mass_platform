package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWorkerBudgetPolicyTest {

    private final DefaultWorkerBudgetPolicy policy = new DefaultWorkerBudgetPolicy();

    @Test
    void interactiveWorkloadUsesSmallDefaultBudget() {
        WorkerBudgetDecision decision = policy.resolve(task(TaskWorkloadClass.INTERACTIVE), 3, 1);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS, decision.workerBudget());
        assertEquals(1, decision.currentTaskWorkerCount());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_INTERACTIVE_MAX_WORKERS - 1, decision.availableWorkerCount());
        assertFalse(decision.budgetLimited());
    }

    @Test
    void bulkAndUnknownWorkloadUseBulkDefaultBudget() {
        WorkerBudgetDecision bulk = policy.resolve(task(TaskWorkloadClass.BULK), 30, 0);
        WorkerBudgetDecision unknown = policy.resolve(task(null), 30, 0);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, bulk.workerBudget());
        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, unknown.workerBudget());
        assertTrue(bulk.budgetLimited());
        assertTrue(unknown.budgetLimited());
    }

    @Test
    void currentTaskWorkerCountReducesAvailableBudget() {
        WorkerBudgetDecision decision = policy.resolve(task(TaskWorkloadClass.BULK), 5, 19);

        assertEquals(DefaultWorkerBudgetPolicy.DEFAULT_BULK_MAX_WORKERS, decision.workerBudget());
        assertEquals(19, decision.currentTaskWorkerCount());
        assertEquals(1, decision.availableWorkerCount());
        assertTrue(decision.budgetLimited());
    }

    @Test
    void exhaustedBudgetHasNoAvailableWorkers() {
        WorkerBudgetDecision decision = policy.resolve(task(TaskWorkloadClass.INTERACTIVE), 1, 5);

        assertEquals(0, decision.availableWorkerCount());
        assertTrue(decision.budgetLimited());
    }

    private Task task(TaskWorkloadClass workloadClass) {
        Task task = new Task();
        task.setTid("task-1");
        task.getExecutionSpec().setWorkloadClass(workloadClass);
        return task;
    }
}
