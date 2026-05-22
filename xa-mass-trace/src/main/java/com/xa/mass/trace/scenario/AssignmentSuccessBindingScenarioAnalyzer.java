package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Map;

final class AssignmentSuccessBindingScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "assignment-success-binding";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "assignment-success-binding requires at least one accepted worker match");
        requireEvent(counts, issues, "WORKER_LOCK_ACQUIRED",
                "assignment-success-binding requires worker lock acquisition");

        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY") && result(row, "SUCCESS"))) {
            issues.add(new TraceScenarioIssue("MISSING_SUCCESS_ASSIGNMENT_SUMMARY",
                    "Expected ASSIGNMENT_SUMMARY with result=SUCCESS"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY") && result(row, "SUCCESS"))) {
            issues.add(new TraceScenarioIssue("MISSING_SUCCESS_DISPATCH_BINDING_SUMMARY",
                    "Expected DISPATCH_BINDING_SUMMARY with result=SUCCESS"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY")
                && row.dispatchedMessageCount() != null
                && row.dispatchedMessageCount() > 0)) {
            issues.add(new TraceScenarioIssue("NO_DISPATCHED_MESSAGES",
                    "Expected dispatch binding summary with dispatchedMessageCount > 0"));
        }
        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && row.usedWorkerCount() != null
                && row.usedWorkerCount() > 0)) {
            issues.add(new TraceScenarioIssue("NO_USED_WORKERS",
                    "Expected assignment summary with usedWorkerCount > 0"));
        }
        if (!has(rows, row -> transition(row, "TASK_STATUS_TRANSITION", "READY", "RUNNING"))) {
            issues.add(new TraceScenarioIssue("MISSING_READY_TO_RUNNING",
                    "Expected task transition READY -> RUNNING"));
        }
        if (!has(rows, row -> transition(row, "TASK_WORK_ATTEMPT_STATUS_TRANSITION", "CREATED", "LEASED"))) {
            issues.add(new TraceScenarioIssue("MISSING_ATTEMPT_CREATED_TO_LEASED",
                    "Expected attempt transition CREATED -> LEASED"));
        }
        if (!has(rows, row -> transition(row, "TASK_WORK_ATTEMPT_STATUS_TRANSITION", "LEASED", "DISPATCHED"))) {
            issues.add(new TraceScenarioIssue("MISSING_ATTEMPT_LEASED_TO_DISPATCHED",
                    "Expected attempt transition LEASED -> DISPATCHED"));
        }
    }
}
