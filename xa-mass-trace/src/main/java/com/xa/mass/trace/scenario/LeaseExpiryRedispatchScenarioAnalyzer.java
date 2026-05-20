package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Map;

final class LeaseExpiryRedispatchScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "lease-expiry-redispatch";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "LEASE_EXPIRED",
                "lease-expiry-redispatch requires a lease expiry event");
        requireEvent(counts, issues, "TASK_WORK_ATTEMPT_CLOSED",
                "lease-expiry-redispatch requires the expired attempt to close before takeover");

        long successBindings = count(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY") && result(row, "SUCCESS"));
        if (successBindings < 2L) {
            issues.add(new TraceScenarioIssue("MISSING_INITIAL_AND_REDISPATCH_BINDINGS",
                    "Expected one successful binding before expiry and one successful binding after expiry"));
        }

        Long leaseExpiredTs = rows.stream()
                .filter(row -> event(row, "LEASE_EXPIRED"))
                .map(TraceAssignmentRow::ts)
                .findFirst()
                .orElse(null);
        if (leaseExpiredTs != null) {
            boolean hasBindingBeforeExpiry = has(rows, row ->
                    event(row, "DISPATCH_BINDING_SUMMARY")
                            && result(row, "SUCCESS")
                            && row.ts() < leaseExpiredTs);
            boolean hasBindingAfterExpiry = has(rows, row ->
                    event(row, "DISPATCH_BINDING_SUMMARY")
                            && result(row, "SUCCESS")
                            && row.ts() > leaseExpiredTs);
            if (!hasBindingBeforeExpiry || !hasBindingAfterExpiry) {
                issues.add(new TraceScenarioIssue("MISSING_BINDING_AROUND_EXPIRY",
                        "Expected successful dispatch binding both before and after lease expiry"));
            }
        }
    }
}
