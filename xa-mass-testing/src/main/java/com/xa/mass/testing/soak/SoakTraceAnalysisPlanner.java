package com.xa.mass.testing.soak;

import java.util.ArrayList;
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
                    "late-worker-backfill",
                    lateWorkerProofTaskId + "," + lateWorkerProofWorkerId
            ));
        }
        firstTaskIdForTerminalReason(taskPlans, "ALL_MESSAGES_FAILED")
                .ifPresent(taskId -> plans.add(new TraceAnalysisPlan(
                        "all-failed-terminal-convergence",
                        taskId
                )));
        firstTaskIdForTerminalReason(taskPlans, "MIXED_MESSAGE_RESULTS")
                .ifPresent(taskId -> plans.add(new TraceAnalysisPlan(
                        "mixed-result-terminal-convergence",
                        taskId
                )));
        return List.copyOf(plans);
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
