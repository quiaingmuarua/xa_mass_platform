package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AssignmentRetryRedispatchScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "assignment-retry-redispatch";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        boolean hasInitialFailureOrSkip = has(rows, row ->
                event(row, "ASSIGNMENT_SUMMARY")
                        && ("SKIPPED".equals(row.result()) || "FAILED".equals(row.result()) || "REJECTED".equals(row.result())));
        if (!hasInitialFailureOrSkip) {
            issues.add(new TraceScenarioIssue("MISSING_INITIAL_ASSIGNMENT_FAILURE_OR_SKIP",
                    "Expected an initial skipped, failed, or rejected assignment summary"));
        }

        boolean hasRetryEvidence = counts.getOrDefault("ASSIGNMENT_RETRY_SCHEDULED", 0L) > 0L
                || has(rows, row -> event(row, "ASSIGNMENT_QUEUE_SNAPSHOT") && retryQueueAction(row.queueAction()));
        if (!hasRetryEvidence) {
            issues.add(new TraceScenarioIssue("MISSING_RETRY_OR_REQUEUE_EVIDENCE",
                    "Expected ASSIGNMENT_RETRY_SCHEDULED or assignment queue retry/requeue action"));
        }

        long assignmentAttempts = count(rows, row -> event(row, "ASSIGNMENT_SUMMARY"));
        if (assignmentAttempts < 2L) {
            issues.add(new TraceScenarioIssue("MISSING_REDISPATCH_ASSIGNMENT_ATTEMPT",
                    "Expected at least two assignment summary events"));
        }

        boolean hasSuccessBinding = has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY") && result(row, "SUCCESS"));
        boolean hasAnySuccess = has(rows, row -> event(row, "ASSIGNMENT_SUMMARY") && result(row, "SUCCESS"));
        if (hasAnySuccess && !hasSuccessBinding) {
            issues.add(new TraceScenarioIssue("MISSING_SUCCESS_BINDING_AFTER_REDISPATCH",
                    "A successful redispatch assignment must include DISPATCH_BINDING_SUMMARY result=SUCCESS"));
        }
    }

    private boolean retryQueueAction(String queueAction) {
        if (queueAction == null) {
            return false;
        }
        String normalized = queueAction.toLowerCase(Locale.ROOT);
        return normalized.contains("retry") || normalized.contains("requeue");
    }
}
