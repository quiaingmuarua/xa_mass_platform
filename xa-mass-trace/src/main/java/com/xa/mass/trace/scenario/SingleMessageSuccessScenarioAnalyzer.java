package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;
import java.util.Map;

final class SingleMessageSuccessScenarioAnalyzer extends AbstractTimelineScenarioAnalyzer {

    @Override
    public String id() {
        return "single-message-success";
    }

    @Override
    protected void analyzeTimeline(List<TraceTimelineRow> rows,
                                   Map<String, Long> counts,
                                   List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "TASK_STATUS_TRANSITION",
                "single-message-success requires at least one task lifecycle transition");
        requireEvent(counts, issues, "CALLBACK_ACCEPTED",
                "single-message-success requires a successful callback ingest");
        requireEvent(counts, issues, "TASK_TERMINAL_CLOSED",
                "single-message-success requires terminal convergence");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_SUCCEEDED");
        TraceSequenceVerifier.requireOrdered(
                rows,
                issues,
                "SINGLE_MESSAGE_SUCCESS_SEQUENCE_MISMATCH",
                "single-message-success requires assignment, callback ingest, and terminal convergence in order",
                TraceSequenceExpectation.event("TASK_STATUS_TRANSITION"),
                TraceSequenceExpectation.event("CALLBACK_ACCEPTED"),
                TraceSequenceExpectation.event("TASK_TERMINAL_CLOSED")
                        .terminalReason("ALL_MESSAGES_SUCCEEDED"));

        rejectEvent(counts, issues, "CALLBACK_IGNORED_DUPLICATE",
                "single-message-success should not rely on duplicate callback suppression");
        rejectEvent(counts, issues, "CALLBACK_IGNORED_LATE",
                "single-message-success should not emit late callback suppression");
        rejectEvent(counts, issues, "CALLBACK_REJECTED_INVALID_STATE",
                "single-message-success should not reject callback state");
        rejectEvent(counts, issues, "CALLBACK_REJECTED_NO_ACTIVE_LEASE",
                "single-message-success should not lose the active lease before result ingest");
    }
}
