package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AssignmentMinWorkerGateScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "assignment-min-worker-gate";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY") && result(row, "SKIPPED"))) {
            issues.add(new TraceScenarioIssue("MISSING_SKIPPED_ASSIGNMENT_SUMMARY",
                    "Expected ASSIGNMENT_SUMMARY with result=SKIPPED"));
        }
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && row.matchedWorkerCount() != null
                && row.requiredStartWorkerCount() != null
                && row.matchedWorkerCount() < row.requiredStartWorkerCount())) {
            issues.add(new TraceScenarioIssue("MIN_WORKER_GATE_COUNTS_NOT_OBSERVED",
                    "Expected matchedWorkerCount < requiredStartWorkerCount"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_SKIPPED") && minimumGateReason(row.reason()))) {
            issues.add(new TraceScenarioIssue("MISSING_MIN_GATE_SKIP_REASON",
                    "Expected DISPATCH_SKIPPED reason to describe minimum worker gate"));
        }
        rejectMatching(rows, issues, "UNEXPECTED_READY_TO_RUNNING",
                row -> transition(row, "TASK_STATUS_TRANSITION", "READY", "RUNNING"),
                "Minimum worker gate scenario must not transition READY -> RUNNING");
        rejectMatching(rows, issues, "UNEXPECTED_SUCCESS_DISPATCH_BINDING",
                row -> event(row, "DISPATCH_BINDING_SUMMARY") && result(row, "SUCCESS"),
                "Minimum worker gate scenario must not produce a successful dispatch binding");
    }

    private boolean minimumGateReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("minimum") || normalized.contains("gate");
    }
}
