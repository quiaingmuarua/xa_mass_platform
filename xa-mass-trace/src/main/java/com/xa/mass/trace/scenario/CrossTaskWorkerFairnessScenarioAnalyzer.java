package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;
import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class CrossTaskWorkerFairnessScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public String id() {
        return "cross-task-worker-fairness";
    }

    @Override
    public TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                       TraceSource source,
                                       String taskId) throws Exception {
        TaskPair pair = parseTaskPair(taskId);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (pair == null) {
            issues.add(new TraceScenarioIssue("INVALID_TASK_PAIR",
                    "cross-task-worker-fairness expects --task-id <bulkTaskId>,<interactiveTaskId>"));
            return report(source, taskId, List.of(), List.of(), issues);
        }

        List<TraceAssignmentRow> bulkRows = queryBackend.assignment(source, pair.bulkTaskId(), 2_000);
        List<TraceAssignmentRow> interactiveRows = queryBackend.assignment(source, pair.interactiveTaskId(), 2_000);

        if (bulkRows.isEmpty()) {
            issues.add(new TraceScenarioIssue("MISSING_BULK_ASSIGNMENT_TRACE",
                    "Expected schedule trace rows for bulk task " + pair.bulkTaskId()));
        } else {
            analyzeBulkPressure(bulkRows, issues);
        }
        if (interactiveRows.isEmpty()) {
            issues.add(new TraceScenarioIssue("MISSING_INTERACTIVE_ASSIGNMENT_TRACE",
                    "Expected schedule trace rows for interactive task " + pair.interactiveTaskId()));
        } else {
            analyzeInteractiveProgress(interactiveRows, issues);
        }
        if (!bulkRows.isEmpty() && !interactiveRows.isEmpty()) {
            analyzeWorkerSeparation(bulkRows, interactiveRows, issues);
        }

        return report(source, taskId, bulkRows, interactiveRows, issues);
    }

    private void analyzeBulkPressure(List<TraceAssignmentRow> rows,
                                     List<TraceScenarioIssue> issues) {
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && result(row, "SUCCESS")
                && "BULK".equals(row.workloadClass()))) {
            issues.add(new TraceScenarioIssue("MISSING_BULK_SUCCESS_ASSIGNMENT_SUMMARY",
                    "Expected bulk ASSIGNMENT_SUMMARY result=SUCCESS"));
        }
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && Boolean.TRUE.equals(row.budgetLimited()))) {
            issues.add(new TraceScenarioIssue("MISSING_BULK_BUDGET_LIMIT",
                    "Expected bulk assignment summary with budgetLimited=true"));
        }
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && row.pendingDispatchCount() != null
                && row.workerBudget() != null
                && row.pendingDispatchCount() > row.workerBudget())) {
            issues.add(new TraceScenarioIssue("MISSING_BULK_BACKLOG_PRESSURE",
                    "Expected bulk pendingDispatchCount greater than workerBudget"));
        }
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && row.workerBudget() != null
                && row.dispatchCandidateCount() != null
                && row.usedWorkerCount() != null
                && row.dispatchCandidateCount() <= row.workerBudget()
                && row.usedWorkerCount() <= row.workerBudget())) {
            issues.add(new TraceScenarioIssue("BULK_DISPATCH_EXCEEDS_BUDGET",
                    "Expected bulk dispatchCandidateCount and usedWorkerCount to stay within workerBudget"));
        }
    }

    private void analyzeInteractiveProgress(List<TraceAssignmentRow> rows,
                                            List<TraceScenarioIssue> issues) {
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && result(row, "SUCCESS")
                && "INTERACTIVE".equals(row.workloadClass())
                && row.usedWorkerCount() != null
                && row.usedWorkerCount() > 0)) {
            issues.add(new TraceScenarioIssue("MISSING_INTERACTIVE_SUCCESS_ASSIGNMENT_SUMMARY",
                    "Expected interactive ASSIGNMENT_SUMMARY result=SUCCESS with usedWorkerCount > 0"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY")
                && result(row, "SUCCESS")
                && row.dispatchedMessageCount() != null
                && row.dispatchedMessageCount() > 0)) {
            issues.add(new TraceScenarioIssue("MISSING_INTERACTIVE_SUCCESS_BINDING",
                    "Expected interactive DISPATCH_BINDING_SUMMARY result=SUCCESS with dispatched work"));
        }
        if (!has(rows, row -> event(row, "WORKER_MATCH_ACCEPTED"))) {
            issues.add(new TraceScenarioIssue("MISSING_INTERACTIVE_ACCEPTED_WORKER",
                    "Expected interactive WORKER_MATCH_ACCEPTED evidence"));
        }
        if (!has(rows, row -> transition(row, "TASK_STATUS_TRANSITION", "READY", "RUNNING"))) {
            issues.add(new TraceScenarioIssue("MISSING_INTERACTIVE_READY_TO_RUNNING",
                    "Expected interactive task transition READY -> RUNNING"));
        }
    }

    private void analyzeWorkerSeparation(List<TraceAssignmentRow> bulkRows,
                                         List<TraceAssignmentRow> interactiveRows,
                                         List<TraceScenarioIssue> issues) {
        Set<String> bulkAcceptedWorkers = acceptedWorkerIds(bulkRows);
        Set<String> interactiveAcceptedWorkers = acceptedWorkerIds(interactiveRows);
        if (bulkAcceptedWorkers.isEmpty() || interactiveAcceptedWorkers.isEmpty()) {
            return;
        }
        Set<String> overlap = new LinkedHashSet<>(interactiveAcceptedWorkers);
        overlap.retainAll(bulkAcceptedWorkers);
        if (!overlap.isEmpty()) {
            issues.add(new TraceScenarioIssue("INTERACTIVE_REUSED_BULK_WORKER",
                    "Expected interactive assignment to use worker capacity not already accepted by bulk task: "
                            + String.join(",", overlap)));
        }
    }

    private TraceScenarioReport report(TraceSource source,
                                       String taskId,
                                       List<TraceAssignmentRow> bulkRows,
                                       List<TraceAssignmentRow> interactiveRows,
                                       List<TraceScenarioIssue> issues) {
        List<TraceAssignmentRow> rows = new ArrayList<>(bulkRows.size() + interactiveRows.size());
        rows.addAll(bulkRows);
        rows.addAll(interactiveRows);
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

    private TaskPair parseTaskPair(String taskId) {
        if (taskId == null) {
            return null;
        }
        String[] parts = taskId.split(",");
        if (parts.length != 2) {
            return null;
        }
        String bulkTaskId = parts[0].trim();
        String interactiveTaskId = parts[1].trim();
        if (bulkTaskId.isEmpty() || interactiveTaskId.isEmpty()) {
            return null;
        }
        return new TaskPair(bulkTaskId, interactiveTaskId);
    }

    private boolean has(List<TraceAssignmentRow> rows, Predicate<TraceAssignmentRow> predicate) {
        return rows.stream().anyMatch(predicate);
    }

    private boolean event(TraceAssignmentRow row, String eventType) {
        return row != null && eventType.equals(row.eventType());
    }

    private boolean result(TraceAssignmentRow row, String result) {
        return row != null && result.equals(row.result());
    }

    private boolean transition(TraceAssignmentRow row, String eventType, String src, String dst) {
        return event(row, eventType) && src.equals(row.src()) && dst.equals(row.dst());
    }

    private Set<String> acceptedWorkerIds(List<TraceAssignmentRow> rows) {
        return rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .map(TraceAssignmentRow::workerId)
                .filter(workerId -> workerId != null && !workerId.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Long> countByType(List<TraceAssignmentRow> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceAssignmentRow row : rows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    private record TaskPair(String bulkTaskId, String interactiveTaskId) {
    }
}
