package com.xa.mass.testing.soak;

import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class SoakTraceAnalysisPlanner {

    private SoakTraceAnalysisPlanner() {
    }

    static List<TraceAnalysisPlan> plan(List<SoakTaskPlanRef> taskPlans,
                                        boolean requireLateWorkerWork,
                                        String lateWorkerProofTaskId,
                                        String lateWorkerProofWorkerId) {
        Set<TraceAnalysisPlan> plans = new LinkedHashSet<>();
        if (requireLateWorkerWork
                && present(lateWorkerProofTaskId)
                && present(lateWorkerProofWorkerId)) {
            plans.add(new TraceAnalysisPlan(
                    WorkerFaultScenarioIndex.TraceAnalyzerScenario.LATE_WORKER_BACKFILL.scenarioId(),
                    lateWorkerProofTaskId + "," + lateWorkerProofWorkerId
            ));
        }
        addTerminalReasonPlan(plans, taskPlans, "ALL_MESSAGES_FAILED");
        addTerminalReasonPlan(plans, taskPlans, "MIXED_MESSAGE_RESULTS");
        return List.copyOf(plans);
    }

    private static void addTerminalReasonPlan(Set<TraceAnalysisPlan> plans,
                                              List<SoakTaskPlanRef> taskPlans,
                                              String terminalReason) {
        firstTaskIdForTerminalReason(taskPlans, terminalReason)
                .ifPresent(taskId -> WorkerFaultScenarioIndex.traceAnalyzerForSoakTerminalReason(terminalReason)
                        .ifPresent(analyzer -> plans.add(new TraceAnalysisPlan(analyzer.scenarioId(), taskId))));
    }

    private static java.util.Optional<String> firstTaskIdForTerminalReason(List<SoakTaskPlanRef> taskPlans,
                                                                           String terminalReason) {
        for (SoakTaskPlanRef plan : taskPlans) {
            if (terminalReason.equals(plan.expectedTerminalReason()) && present(plan.taskId())) {
                return java.util.Optional.of(plan.taskId());
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    record TraceAnalysisPlan(String scenarioId, String sourceId) {
    }

    record SoakTaskPlanRef(String taskId, String expectedTerminalReason) {
    }
}
