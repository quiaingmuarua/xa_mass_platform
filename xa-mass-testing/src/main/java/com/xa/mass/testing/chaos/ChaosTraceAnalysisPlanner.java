package com.xa.mass.testing.chaos;

import com.xa.mass.trace.operator.TraceAnalyzeRequest;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.trace.operator.TraceOperatorService;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ChaosTraceAnalysisPlanner {

    private ChaosTraceAnalysisPlanner() {
    }

    static List<TraceAnalyzeResponse> analyze(Path traceDir,
                                              ChaosProofProfile profile,
                                              String taskId) throws Exception {
        TraceOperatorService traceOperator = new TraceOperatorService();
        List<TraceAnalysisPlan> plans = plan(profile, taskId);
        java.util.ArrayList<TraceAnalyzeResponse> responses = new java.util.ArrayList<>(plans.size());
        for (TraceAnalysisPlan plan : plans) {
            responses.add(traceOperator.analyze(new TraceAnalyzeRequest(
                    traceDir.toString(),
                    plan.scenarioId(),
                    plan.sourceId()
            )));
        }
        return List.copyOf(responses);
    }

    static void requireAllOk(List<TraceAnalyzeResponse> analyses) {
        for (TraceAnalyzeResponse analysis : analyses) {
            if (!analysis.ok()) {
                throw new IllegalStateException("trace analyzer " + analysis.scenarioId()
                        + " failed: " + analysis.issues());
            }
        }
    }

    static List<TraceAnalysisPlan> plan(ChaosProofProfile profile, String taskId) {
        Set<TraceAnalysisPlan> plans = new LinkedHashSet<>();
        if (present(taskId)) {
            switch (profile) {
                case ALL_FAILED_TERMINAL_CONVERGENCE ->
                        plans.add(new TraceAnalysisPlan("all-failed-terminal-convergence", taskId));
                case MIXED_RESULT_TERMINAL_CONVERGENCE ->
                        plans.add(new TraceAnalysisPlan("mixed-result-terminal-convergence", taskId));
                case LEASE_EXPIRY_REDISPATCH ->
                        plans.add(new TraceAnalysisPlan("lease-expiry-redispatch", taskId));
                case LATE_STALE_RESULT_REPLAY ->
                        plans.add(new TraceAnalysisPlan("late-stale-result-replay", taskId));
            }
        }
        return List.copyOf(plans);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    enum ChaosProofProfile {
        ALL_FAILED_TERMINAL_CONVERGENCE,
        MIXED_RESULT_TERMINAL_CONVERGENCE,
        LEASE_EXPIRY_REDISPATCH,
        LATE_STALE_RESULT_REPLAY
    }

    record TraceAnalysisPlan(String scenarioId, String sourceId) {
    }
}
