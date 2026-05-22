package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceSource;
import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractTimelineScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public final TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                             TraceSource source,
                                             String taskId) throws Exception {
        List<TraceTimelineRow> rows = queryBackend.timeline(source, taskId, null, 2_000);
        Map<String, Long> counts = countByType(rows);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (rows.isEmpty()) {
            issues.add(new TraceScenarioIssue("EMPTY_TIMELINE",
                    "No trace events found for taskId=" + taskId));
        } else {
            analyzeTimeline(rows, counts, issues);
        }
        return new TraceScenarioReport(
                id(),
                taskId,
                source.inputPath().toString(),
                issues.isEmpty(),
                rows.size(),
                Map.copyOf(counts),
                List.copyOf(issues)
        );
    }

    protected abstract void analyzeTimeline(List<TraceTimelineRow> rows,
                                            Map<String, Long> counts,
                                            List<TraceScenarioIssue> issues);

    protected final void requireEvent(Map<String, Long> counts,
                                      List<TraceScenarioIssue> issues,
                                      String eventType,
                                      String message) {
        if (counts.getOrDefault(eventType, 0L) <= 0L) {
            issues.add(new TraceScenarioIssue("MISSING_" + eventType, message));
        }
    }

    protected final void rejectEvent(Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues,
                                     String eventType,
                                     String message) {
        long count = counts.getOrDefault(eventType, 0L);
        if (count > 0L) {
            issues.add(new TraceScenarioIssue("UNEXPECTED_" + eventType,
                    message + " (observed " + count + ")"));
        }
    }

    protected final void requireTerminalReason(List<TraceTimelineRow> rows,
                                               List<TraceScenarioIssue> issues,
                                               String expected) {
        boolean matched = rows.stream()
                .filter(row -> "TASK_TERMINAL_CLOSED".equals(row.eventType()))
                .anyMatch(row -> expected.equals(row.terminalReason()));
        if (!matched) {
            issues.add(new TraceScenarioIssue(
                    "TERMINAL_REASON_MISMATCH",
                    "Expected TASK_TERMINAL_CLOSED terminalReason=" + expected));
        }
    }

    private Map<String, Long> countByType(List<TraceTimelineRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceTimelineRow row : rows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        return counts;
    }
}
