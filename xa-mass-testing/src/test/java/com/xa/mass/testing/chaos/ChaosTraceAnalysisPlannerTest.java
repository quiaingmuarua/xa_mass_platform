package com.xa.mass.testing.chaos;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChaosTraceAnalysisPlannerTest {

    @Test
    void plansAllFailedTerminalConvergenceAnalyzer() {
        List<ChaosTraceAnalysisPlanner.TraceAnalysisPlan> plans = ChaosTraceAnalysisPlanner.plan(
                ChaosTraceAnalysisPlanner.ChaosProofProfile.ALL_FAILED_TERMINAL_CONVERGENCE,
                "task-all-failed");

        assertEquals(List.of(
                new ChaosTraceAnalysisPlanner.TraceAnalysisPlan("all-failed-terminal-convergence", "task-all-failed")
        ), plans);
    }

    @Test
    void plansMixedResultTerminalConvergenceAnalyzer() {
        List<ChaosTraceAnalysisPlanner.TraceAnalysisPlan> plans = ChaosTraceAnalysisPlanner.plan(
                ChaosTraceAnalysisPlanner.ChaosProofProfile.MIXED_RESULT_TERMINAL_CONVERGENCE,
                "task-mixed");

        assertEquals(List.of(
                new ChaosTraceAnalysisPlanner.TraceAnalysisPlan("mixed-result-terminal-convergence", "task-mixed")
        ), plans);
    }

    @Test
    void plansLeaseExpiryRedispatchAnalyzer() {
        List<ChaosTraceAnalysisPlanner.TraceAnalysisPlan> plans = ChaosTraceAnalysisPlanner.plan(
                ChaosTraceAnalysisPlanner.ChaosProofProfile.LEASE_EXPIRY_REDISPATCH,
                "task-retry");

        assertEquals(List.of(
                new ChaosTraceAnalysisPlanner.TraceAnalysisPlan("lease-expiry-redispatch", "task-retry")
        ), plans);
    }

    @Test
    void plansLateStaleReplayAnalyzer() {
        List<ChaosTraceAnalysisPlanner.TraceAnalysisPlan> plans = ChaosTraceAnalysisPlanner.plan(
                ChaosTraceAnalysisPlanner.ChaosProofProfile.LATE_STALE_RESULT_REPLAY,
                "task-late");

        assertEquals(List.of(
                new ChaosTraceAnalysisPlanner.TraceAnalysisPlan("late-stale-result-replay", "task-late")
        ), plans);
    }

    @Test
    void skipsBlankTaskIds() {
        List<ChaosTraceAnalysisPlanner.TraceAnalysisPlan> plans = ChaosTraceAnalysisPlanner.plan(
                ChaosTraceAnalysisPlanner.ChaosProofProfile.LEASE_EXPIRY_REDISPATCH,
                " ");

        assertEquals(List.of(), plans);
    }
}
