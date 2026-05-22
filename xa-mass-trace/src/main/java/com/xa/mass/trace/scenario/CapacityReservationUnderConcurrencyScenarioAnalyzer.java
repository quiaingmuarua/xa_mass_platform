package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CapacityReservationUnderConcurrencyScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "capacity-reservation-under-concurrency";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "Capacity reservation requires accepted worker match evidence");
        requireEvent(counts, issues, "WORKER_MATCH_REJECTED",
                "Capacity reservation requires rejected worker match evidence under contention");

        List<TraceAssignmentRow> acceptedWithReservation = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .filter(row -> row.workerReservedCount() != null && row.workerReservedCount() > 0)
                .toList();
        if (acceptedWithReservation.isEmpty()) {
            issues.add(new TraceScenarioIssue("MISSING_RESERVED_ACCEPTED_MATCH",
                    "Expected at least one accepted worker match with workerReservedCount > 0"));
        }

        List<TraceAssignmentRow> capacityRejected = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_REJECTED"))
                .filter(row -> contains(row.reason(), "capacity unavailable"))
                .toList();
        if (capacityRejected.isEmpty()) {
            issues.add(new TraceScenarioIssue("MISSING_CAPACITY_REJECTION",
                    "Expected at least one worker match rejection caused by capacity reservation"));
        }
        for (TraceAssignmentRow row : capacityRejected) {
            if (!hasLoadFields(row)) {
                issues.add(new TraceScenarioIssue("CAPACITY_REJECTION_MISSING_LOAD_FIELDS",
                        "Capacity rejection for worker " + row.workerId() + " is missing load/capacity fields"));
                continue;
            }
            if (observedLoad(row) < row.workerDeclaredCapacity()) {
                issues.add(new TraceScenarioIssue("CAPACITY_REJECTION_WITHOUT_FULL_LOAD",
                        "Capacity rejection for worker " + row.workerId()
                                + " did not show active+reserved >= declared capacity"));
            }
        }

        for (TraceAssignmentRow row : rows) {
            if (!event(row, "WORKER_MATCH_ACCEPTED") || !hasLoadFields(row)) {
                continue;
            }
            if (observedLoad(row) > row.workerDeclaredCapacity()) {
                issues.add(new TraceScenarioIssue("ACCEPTED_WORKER_OVER_CAPACITY",
                        "Accepted worker " + row.workerId()
                                + " showed active+reserved greater than declared capacity"));
            }
        }
    }

    private boolean hasLoadFields(TraceAssignmentRow row) {
        return row.workerActiveLeaseCount() != null
                && row.workerReservedCount() != null
                && row.workerDeclaredCapacity() != null;
    }

    private int observedLoad(TraceAssignmentRow row) {
        return row.workerActiveLeaseCount() + row.workerReservedCount();
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }
}
