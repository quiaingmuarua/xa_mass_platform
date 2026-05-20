package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;
import java.util.Map;

final class AllFailedTerminalConvergenceScenarioAnalyzer extends AbstractTimelineScenarioAnalyzer {

    @Override
    public String id() {
        return "all-failed-terminal-convergence";
    }

    @Override
    protected void analyzeTimeline(List<TraceTimelineRow> rows,
                                   Map<String, Long> counts,
                                   List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "CALLBACK_ACCEPTED",
                "all-failed-terminal-convergence requires accepted result callbacks");
        requireEvent(counts, issues, "TASK_WORK_LOGICALLY_FINAL",
                "all-failed-terminal-convergence requires work items to converge to logical finality");
        requireEvent(counts, issues, "TASK_TERMINAL_CLOSED",
                "all-failed-terminal-convergence requires terminal convergence");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_FAILED");

        long acceptedCallbacks = counts.getOrDefault("CALLBACK_ACCEPTED", 0L);
        if (acceptedCallbacks < 2L) {
            issues.add(new TraceScenarioIssue(
                    "INSUFFICIENT_CALLBACK_ACCEPTED",
                    "all-failed-terminal-convergence expects at least two accepted callbacks"));
        }

        long failureFinals = rows.stream()
                .filter(row -> "TASK_WORK_LOGICALLY_FINAL".equals(row.eventType()))
                .filter(row -> "work item reached stable failure".equals(row.reason()))
                .count();
        if (failureFinals < 2L) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_FAILURE_FINALS",
                    "all-failed-terminal-convergence requires failure-side logical final transitions for all failed work"));
        }

        boolean hasSuccessFinal = rows.stream()
                .filter(row -> "TASK_WORK_LOGICALLY_FINAL".equals(row.eventType()))
                .anyMatch(row -> "work item reached stable success".equals(row.reason()));
        if (hasSuccessFinal) {
            issues.add(new TraceScenarioIssue(
                    "UNEXPECTED_SUCCESS_FINAL",
                    "all-failed-terminal-convergence must not include success-side logical final transitions"));
        }

        TraceSequenceVerifier.requireOrdered(
                rows,
                issues,
                "ALL_FAILED_TERMINAL_SEQUENCE_MISMATCH",
                "all-failed-terminal-convergence requires accepted callbacks before all-failed terminal closure",
                TraceSequenceExpectation.event("CALLBACK_ACCEPTED"),
                TraceSequenceExpectation.event("TASK_TERMINAL_CLOSED")
                        .terminalReason("ALL_MESSAGES_FAILED"));

        rejectEvent(counts, issues, "CALLBACK_IGNORED_DUPLICATE",
                "all-failed-terminal-convergence should not rely on duplicate callback suppression");
        rejectEvent(counts, issues, "CALLBACK_IGNORED_LATE",
                "all-failed-terminal-convergence should not rely on late callback suppression");
    }
}
