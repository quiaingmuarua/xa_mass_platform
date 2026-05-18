package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;
import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class LateWorkerBackfillScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public String id() {
        return "late-worker-backfill";
    }

    @Override
    public TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                       TraceSource source,
                                       String taskId) throws Exception {
        Target target = parseTarget(taskId);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (target == null) {
            issues.add(new TraceScenarioIssue("INVALID_LATE_WORKER_BACKFILL_TARGET",
                    "late-worker-backfill expects --task-id <taskId>,<lateWorkerId>"));
            return report(source, taskId, List.of(), issues);
        }

        List<TraceAssignmentRow> rows = queryBackend.assignment(source, target.taskId(), 2_000);
        if (rows.isEmpty()) {
            issues.add(new TraceScenarioIssue("EMPTY_ASSIGNMENT_TRACE",
                    "No schedule/assignment trace events found for taskId=" + target.taskId()));
        } else {
            analyzeRows(rows, target, issues);
        }
        return report(source, taskId, rows, issues);
    }

    private void analyzeRows(List<TraceAssignmentRow> rows,
                             Target target,
                             List<TraceScenarioIssue> issues) {
        List<TraceAssignmentRow> accepted = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .filter(row -> target.lateWorkerId().equals(row.workerId()))
                .toList();
        if (accepted.isEmpty()) {
            issues.add(new TraceScenarioIssue("MISSING_LATE_WORKER_ACCEPTED_MATCH",
                    "Expected WORKER_MATCH_ACCEPTED for late worker " + target.lateWorkerId()));
            return;
        }

        long acceptedAt = accepted.stream()
                .mapToLong(TraceAssignmentRow::ts)
                .min()
                .orElse(Long.MIN_VALUE);
        if (accepted.stream().noneMatch(row -> present(row.workerCandidateSource()))) {
            issues.add(new TraceScenarioIssue("MISSING_LATE_WORKER_CANDIDATE_SOURCE",
                    "Late worker accepted match should carry workerCandidateSource evidence"));
        }
        if (!hasAtOrAfter(rows, acceptedAt, row -> event(row, "ASSIGNMENT_SUMMARY")
                && result(row, "SUCCESS")
                && positive(row.usedWorkerCount()))) {
            issues.add(new TraceScenarioIssue("MISSING_BACKFILL_ASSIGNMENT_SUMMARY",
                    "Expected successful ASSIGNMENT_SUMMARY after late worker accepted match"));
        }
        if (!hasAtOrAfter(rows, acceptedAt, row -> event(row, "DISPATCH_BINDING_SUMMARY")
                && result(row, "SUCCESS")
                && positive(row.dispatchedMessageCount()))) {
            issues.add(new TraceScenarioIssue("MISSING_BACKFILL_DISPATCH_BINDING",
                    "Expected successful DISPATCH_BINDING_SUMMARY with dispatched work after late worker accepted match"));
        }
    }

    private TraceScenarioReport report(TraceSource source,
                                       String taskId,
                                       List<TraceAssignmentRow> rows,
                                       List<TraceScenarioIssue> issues) {
        return new TraceScenarioReport(
                id(),
                taskId,
                source.inputPath().toString(),
                issues.isEmpty(),
                rows.size(),
                countByType(rows),
                List.copyOf(issues)
        );
    }

    private Map<String, Long> countByType(List<TraceAssignmentRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceAssignmentRow row : rows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    private boolean hasAtOrAfter(List<TraceAssignmentRow> rows,
                                 long timestamp,
                                 Predicate<TraceAssignmentRow> predicate) {
        return rows.stream()
                .filter(row -> row.ts() >= timestamp)
                .anyMatch(predicate);
    }

    private boolean event(TraceAssignmentRow row, String eventType) {
        return row != null && eventType.equals(row.eventType());
    }

    private boolean result(TraceAssignmentRow row, String result) {
        return row != null && result.equals(row.result());
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private Target parseTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) {
            return null;
        }
        String taskId = parts[0].trim();
        String workerId = parts[1].trim();
        if (taskId.isBlank() || workerId.isBlank()) {
            return null;
        }
        return new Target(taskId, workerId);
    }

    private record Target(String taskId, String lateWorkerId) {
    }
}
