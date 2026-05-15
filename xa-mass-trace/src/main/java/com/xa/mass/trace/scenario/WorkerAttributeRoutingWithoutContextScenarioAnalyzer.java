package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Map;

final class WorkerAttributeRoutingWithoutContextScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "worker-attribute-routing-without-context";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "worker-attribute-routing-without-context requires accepted worker match evidence");

        List<TraceAssignmentRow> accepted = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .toList();
        if (accepted.stream().noneMatch(this::isStatelessAcceptedMatch)) {
            issues.add(new TraceScenarioIssue("MISSING_STATELESS_WORKER_MATCH",
                    "Expected accepted worker match without workerContextId"));
        }
        if (accepted.stream().anyMatch(row -> present(row.workerContextId()))) {
            issues.add(new TraceScenarioIssue("WORKER_CONTEXT_BACKED_MATCH_OBSERVED",
                    "Accepted worker match must not depend on WorkerContext evidence"));
        }
        if (accepted.stream().noneMatch(row -> Boolean.TRUE.equals(row.workerSchedulingMatchesRoutingCode()))) {
            issues.add(new TraceScenarioIssue("MISSING_WORKER_SCHEDULING_ROUTING_EVIDENCE",
                    "Expected accepted worker match with workerSchedulingMatchesRoutingCode=true"));
        }
        if (accepted.stream().noneMatch(this::hasWorkerSchedulingAttributesEvidence)) {
            issues.add(new TraceScenarioIssue("MISSING_WORKER_SCHEDULING_ATTRIBUTES_EVIDENCE",
                    "Expected accepted worker match to carry worker scheduling attributes or routing tags"));
        }

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
    }

    private boolean isStatelessAcceptedMatch(TraceAssignmentRow row) {
        return row != null && !present(row.workerContextId());
    }

    private boolean hasWorkerSchedulingAttributesEvidence(TraceAssignmentRow row) {
        return row != null
                && (present(row.workerSchedulingRoutingTags())
                || present(row.workerSchedulingAttributes())
                || present(row.workerSchedulingResourceId()));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
