package com.xa.mass.testing.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

class SoakTraceAnalysisPlannerTest {

    @Test
    void plansLateWorkerAndFailureProfileAnalyzers() {
        List<SoakTraceAnalysisPlanner.TraceAnalysisPlan> plans = SoakTraceAnalysisPlanner.plan(
                List.of(
                        new SoakTraceAnalysisPlanner.SoakTaskPlanRef("task-success", "ALL_MESSAGES_SUCCEEDED"),
                        new SoakTraceAnalysisPlanner.SoakTaskPlanRef("task-mixed", "MIXED_MESSAGE_RESULTS"),
                        new SoakTraceAnalysisPlanner.SoakTaskPlanRef("task-failed", "ALL_MESSAGES_FAILED")
                ),
                true,
                "task-late",
                "worker-late"
        );

        assertEquals(List.of(
                new SoakTraceAnalysisPlanner.TraceAnalysisPlan("late-worker-backfill", "task-late,worker-late"),
                new SoakTraceAnalysisPlanner.TraceAnalysisPlan("all-failed-terminal-convergence", "task-failed"),
                new SoakTraceAnalysisPlanner.TraceAnalysisPlan("mixed-result-terminal-convergence", "task-mixed")
        ), plans);
    }

    @Test
    void skipsUnavailableProfiles() {
        List<SoakTraceAnalysisPlanner.TraceAnalysisPlan> plans = SoakTraceAnalysisPlanner.plan(
                List.of(new SoakTraceAnalysisPlanner.SoakTaskPlanRef("task-success", "ALL_MESSAGES_SUCCEEDED")),
                false,
                null,
                null
        );

        assertEquals(List.of(), plans);
    }
}
