package com.xa.mass.testing.soak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakRuntimeInvariantCheckerTest {

    @Test
    void acceptsConvergedRuntimeSnapshot() {
        SoakInvariantReport report = SoakRuntimeInvariantChecker.verify(snapshot());

        assertTrue(report.ok());
        assertEquals(0, report.issues().size());
    }

    @Test
    void reportsRuntimeTruthDriftAsStructuredIssues() {
        SoakRuntimeInvariantChecker.Snapshot broken = new SoakRuntimeInvariantChecker.Snapshot(
                2,
                1,
                8,
                7,
                6,
                6,
                5,
                2,
                1,
                1,
                true,
                false,
                3,
                false,
                true,
                0,
                0,
                2
        );

        SoakInvariantReport report = SoakRuntimeInvariantChecker.verify(broken);

        assertFalse(report.ok());
        assertTrue(hasIssue(report, "TASK_TERMINAL_COUNT_MISMATCH"));
        assertTrue(hasIssue(report, "RUNTIME_WORK_COUNT_MISMATCH"));
        assertTrue(hasIssue(report, "VISIBLE_RESULT_COUNT_MISMATCH"));
        assertTrue(hasIssue(report, "SUCCESS_COUNT_MISMATCH"));
        assertTrue(hasIssue(report, "FAILED_COUNT_MISMATCH"));
        assertTrue(hasIssue(report, "ACTIVE_LEASES_NOT_DRAINED"));
        assertTrue(hasIssue(report, "TRACE_VALIDATION_FAILED"));
        assertTrue(hasIssue(report, "TRACE_EVENTS_DROPPED"));
        assertTrue(hasIssue(report, "TRACE_ANALYSIS_FAILED"));
        assertTrue(hasIssue(report, "LATE_WORKER_RECEIVED_NO_WORK"));
        assertTrue(hasIssue(report, "LATE_WORKER_SUBMITTED_NO_RESULTS"));
        assertTrue(hasIssue(report, "WORKER_FAILURES_OBSERVED"));
    }

    @Test
    void mapsReportToStableJsonShape() {
        SoakInvariantReport report = SoakRuntimeInvariantChecker.verify(snapshot());

        assertEquals(true, report.toMap().get("ok"));
        assertEquals(0, report.toMap().get("issueCount"));
        assertTrue(report.toMap().containsKey("issues"));
    }

    private static SoakRuntimeInvariantChecker.Snapshot snapshot() {
        return new SoakRuntimeInvariantChecker.Snapshot(
                2,
                2,
                8,
                8,
                8,
                6,
                6,
                2,
                2,
                0,
                true,
                true,
                0,
                true,
                true,
                4,
                4,
                0
        );
    }

    private static boolean hasIssue(SoakInvariantReport report, String code) {
        return report.issues().stream().anyMatch(issue -> code.equals(issue.code()));
    }
}
