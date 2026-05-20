package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;
import java.util.Map;

final class LateStaleResultReplayScenarioAnalyzer extends AbstractTimelineScenarioAnalyzer {

    @Override
    public String id() {
        return "late-stale-result-replay";
    }

    @Override
    protected void analyzeTimeline(List<TraceTimelineRow> rows,
                                   Map<String, Long> counts,
                                   List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "LEASE_EXPIRED",
                "late-stale-result-replay requires lease expiry before takeover");
        requireEvent(counts, issues, "TASK_WORK_RETRY_RESET",
                "late-stale-result-replay requires retry reset before takeover");
        requireEvent(counts, issues, "CALLBACK_ACCEPTED",
                "late-stale-result-replay requires an accepted takeover callback");
        requireEvent(counts, issues, "TASK_TERMINAL_CLOSED",
                "late-stale-result-replay requires terminal convergence before replay analysis");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_SUCCEEDED");

        long terminalTs = firstTimestamp(rows, "TASK_TERMINAL_CLOSED");
        long staleReplayTs = firstTimestamp(rows, "CALLBACK_IGNORED_LATE");
        boolean ignoredAsDuplicate = staleReplayTs < 0L && counts.getOrDefault("CALLBACK_IGNORED_DUPLICATE", 0L) > 0L;
        if (staleReplayTs < 0L && !ignoredAsDuplicate) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_LATE_OR_DUPLICATE_REPLAY_SUPPRESSION",
                    "late-stale-result-replay requires the stale replay to be suppressed as late or duplicate"));
        }
        if (terminalTs >= 0L && staleReplayTs >= 0L && staleReplayTs <= terminalTs) {
            issues.add(new TraceScenarioIssue(
                    "STALE_REPLAY_PRECEDED_TERMINAL_CONVERGENCE",
                    "late-stale-result-replay expects the stale replay suppression after TASK_TERMINAL_CLOSED"));
        }

        rejectEvent(counts, issues, "CALLBACK_REJECTED_INVALID_STATE",
                "late-stale-result-replay should suppress stale replay instead of rejecting it as invalid state");
        rejectEvent(counts, issues, "CALLBACK_REJECTED_NO_ACTIVE_LEASE",
                "late-stale-result-replay should recognize stale replay from runtime final residue, not as missing lease");
    }

    private long firstTimestamp(List<TraceTimelineRow> rows, String eventType) {
        return rows.stream()
                .filter(row -> eventType.equals(row.eventType()))
                .mapToLong(TraceTimelineRow::ts)
                .min()
                .orElse(-1L);
    }
}
