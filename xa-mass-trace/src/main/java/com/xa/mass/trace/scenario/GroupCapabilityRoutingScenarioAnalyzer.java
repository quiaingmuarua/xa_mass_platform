package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Map;

final class GroupCapabilityRoutingScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "group-capability-routing";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "group-capability-routing requires accepted worker match evidence");

        List<TraceAssignmentRow> accepted = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .toList();
        if (accepted.stream().noneMatch(this::isGroupSelectorAcceptedMatch)) {
            issues.add(new TraceScenarioIssue("MISSING_GROUP_SELECTOR_ACCEPTED_MATCH",
                    "Expected accepted worker match with group-selector candidate source, workerGroupId, and eventBindingKey"));
        }
        if (accepted.stream().anyMatch(row -> !isGroupSelectorAcceptedMatch(row))) {
            issues.add(new TraceScenarioIssue("ACCEPTED_MATCH_MISSING_GROUP_ROUTING_EVIDENCE",
                    "Every accepted worker match in this scenario must carry group-selector routing evidence"));
        }
        if (accepted.stream().noneMatch(this::hasWorkerSchedulingEvidence)) {
            issues.add(new TraceScenarioIssue("MISSING_WORKER_SCHEDULING_EVIDENCE",
                    "Expected accepted worker match to carry worker scheduling evidence"));
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

    private boolean isGroupSelectorAcceptedMatch(TraceAssignmentRow row) {
        return row != null
                && isGroupSelectorSource(row.workerCandidateSource())
                && present(row.workerGroupId())
                && present(row.eventBindingKey())
                && row.eventBindingKey().contains(":")
                && present(row.workerId());
    }

    private boolean isGroupSelectorSource(String source) {
        return "GROUP_SELECTOR".equals(source)
                || "GROUP_SELECTOR_WITH_NODE".equals(source)
                || "TARGET_WORKER".equals(source);
    }

    private boolean hasWorkerSchedulingEvidence(TraceAssignmentRow row) {
        return row != null
                && (present(row.workerSchedulingResourceId())
                || present(row.workerSchedulingRoutingTags())
                || present(row.workerSchedulingAttributes()));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
