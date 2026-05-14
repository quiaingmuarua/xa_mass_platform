package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;
import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

abstract class AbstractAssignmentScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public final TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                             TraceSource source,
                                             String taskId) throws Exception {
        List<TraceAssignmentRow> rows = queryBackend.assignment(source, taskId, 2_000);
        Map<String, Long> counts = countByType(rows);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (rows.isEmpty()) {
            issues.add(new TraceScenarioIssue("EMPTY_ASSIGNMENT_TRACE",
                    "No schedule/assignment trace events found for taskId=" + taskId));
        } else {
            analyzeAssignment(rows, counts, issues);
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

    protected abstract void analyzeAssignment(List<TraceAssignmentRow> rows,
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

    protected final void rejectMatching(List<TraceAssignmentRow> rows,
                                        List<TraceScenarioIssue> issues,
                                        String code,
                                        Predicate<TraceAssignmentRow> predicate,
                                        String message) {
        long matches = rows.stream().filter(predicate).count();
        if (matches > 0L) {
            issues.add(new TraceScenarioIssue(code, message + " (observed " + matches + ")"));
        }
    }

    protected final boolean has(List<TraceAssignmentRow> rows, Predicate<TraceAssignmentRow> predicate) {
        return rows.stream().anyMatch(predicate);
    }

    protected final long count(List<TraceAssignmentRow> rows, Predicate<TraceAssignmentRow> predicate) {
        return rows.stream().filter(predicate).count();
    }

    protected final boolean event(TraceAssignmentRow row, String eventType) {
        return row != null && eventType.equals(row.eventType());
    }

    protected final boolean result(TraceAssignmentRow row, String result) {
        return row != null && result.equals(row.result());
    }

    protected final boolean transition(TraceAssignmentRow row, String eventType, String src, String dst) {
        return event(row, eventType) && src.equals(row.src()) && dst.equals(row.dst());
    }

    private Map<String, Long> countByType(List<TraceAssignmentRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceAssignmentRow row : rows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        return counts;
    }
}
