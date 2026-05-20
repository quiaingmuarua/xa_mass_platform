package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;
import java.util.Map;

final class MixedResultTerminalConvergenceScenarioAnalyzer extends AbstractTimelineScenarioAnalyzer {

    @Override
    public String id() {
        return "mixed-result-terminal-convergence";
    }

    @Override
    protected void analyzeTimeline(List<TraceTimelineRow> rows,
                                   Map<String, Long> counts,
                                   List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "CALLBACK_ACCEPTED",
                "mixed-result-terminal-convergence requires accepted result callbacks");
        requireEvent(counts, issues, "TASK_WORK_LOGICALLY_FINAL",
                "mixed-result-terminal-convergence requires work items to converge to logical finality");
        requireEvent(counts, issues, "TASK_TERMINAL_CLOSED",
                "mixed-result-terminal-convergence requires terminal convergence");
        requireTerminalReason(rows, issues, "MIXED_MESSAGE_RESULTS");

        long acceptedCallbacks = counts.getOrDefault("CALLBACK_ACCEPTED", 0L);
        if (acceptedCallbacks < 2L) {
            issues.add(new TraceScenarioIssue(
                    "INSUFFICIENT_CALLBACK_ACCEPTED",
                    "mixed-result-terminal-convergence expects at least two accepted callbacks"));
        }

        boolean hasSuccessFinal = rows.stream()
                .filter(row -> "TASK_WORK_LOGICALLY_FINAL".equals(row.eventType()))
                .anyMatch(row -> "work item reached stable success".equals(row.reason()));
        if (!hasSuccessFinal) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_SUCCESS_FINAL",
                    "mixed-result-terminal-convergence requires a success-side logical final transition"));
        }

        boolean hasFailureFinal = rows.stream()
                .filter(row -> "TASK_WORK_LOGICALLY_FINAL".equals(row.eventType()))
                .anyMatch(row -> "work item reached stable failure".equals(row.reason()));
        if (!hasFailureFinal) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_FAILURE_FINAL",
                    "mixed-result-terminal-convergence requires a failure-side logical final transition"));
        }

        TraceSequenceVerifier.requireOrdered(
                rows,
                issues,
                "MIXED_RESULT_TERMINAL_SEQUENCE_MISMATCH",
                "mixed-result-terminal-convergence requires accepted callbacks before mixed terminal closure",
                TraceSequenceExpectation.event("CALLBACK_ACCEPTED"),
                TraceSequenceExpectation.event("TASK_TERMINAL_CLOSED")
                        .terminalReason("MIXED_MESSAGE_RESULTS"));

        rejectEvent(counts, issues, "CALLBACK_IGNORED_DUPLICATE",
                "mixed-result-terminal-convergence should not rely on duplicate callback suppression");
        rejectEvent(counts, issues, "CALLBACK_IGNORED_LATE",
                "mixed-result-terminal-convergence should not rely on late callback suppression");
    }
}
