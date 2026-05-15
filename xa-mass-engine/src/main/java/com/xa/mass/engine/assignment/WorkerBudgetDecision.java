package com.xa.mass.engine.assignment;

public record WorkerBudgetDecision(
        Integer workerBudget,
        int currentTaskWorkerCount,
        int availableWorkerCount,
        boolean budgetLimited
) {
    public WorkerBudgetDecision {
        currentTaskWorkerCount = Math.max(0, currentTaskWorkerCount);
        availableWorkerCount = Math.max(0, availableWorkerCount);
        if (workerBudget != null && workerBudget < 0) {
            workerBudget = 0;
        }
    }

    public static WorkerBudgetDecision unlimited(int currentTaskWorkerCount) {
        return new WorkerBudgetDecision(null, currentTaskWorkerCount, Integer.MAX_VALUE, false);
    }
}
