package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;
import java.util.Map;

final class DuplicateCallbackReplayScenarioAnalyzer extends AbstractTimelineScenarioAnalyzer {

    @Override
    public String id() {
        return "duplicate-callback-replay";
    }

    @Override
    protected void analyzeTimeline(List<TraceTimelineRow> rows,
                                   Map<String, Long> counts,
                                   List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "CALLBACK_ACCEPTED",
                "duplicate-callback-replay requires an original accepted callback");
        requireEvent(counts, issues, "CALLBACK_IGNORED_DUPLICATE",
                "duplicate-callback-replay requires the replayed callback to be suppressed as duplicate");
        requireEvent(counts, issues, "TASK_TERMINAL_CLOSED",
                "duplicate-callback-replay requires terminal convergence before replay analysis");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_SUCCEEDED");

        rejectEvent(counts, issues, "CALLBACK_REJECTED_INVALID_STATE",
                "duplicate callback replay should be treated as duplicate suppression, not invalid-state rejection");
        rejectEvent(counts, issues, "CALLBACK_REJECTED_NO_ACTIVE_LEASE",
                "duplicate callback replay should be recognized from recent final/runtime residue, not as missing lease");
    }
}
