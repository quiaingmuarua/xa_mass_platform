package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BackgroundWorkerSharingScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "background-worker-sharing";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "background-worker-sharing requires accepted worker match evidence");
        requireEvent(counts, issues, "ASSIGNMENT_SUMMARY",
                "background-worker-sharing requires assignment summary evidence");
        requireEvent(counts, issues, "DISPATCH_BINDING_SUMMARY",
                "background-worker-sharing requires dispatch binding evidence");

        if (counts.getOrDefault("WORKER_LOCK_ACQUIRED", 0L) > 0L) {
            issues.add(new TraceScenarioIssue("BACKGROUND_WORKER_LOCK_ACQUIRED",
                    "Expected background assignment to avoid long-lived worker lock acquisition"));
        }
        if (counts.getOrDefault("WORKER_LOCK_RELEASED", 0L) > 0L) {
            issues.add(new TraceScenarioIssue("BACKGROUND_WORKER_LOCK_RELEASED",
                    "Expected background assignment to avoid worker lock release evidence"));
        }

        List<TraceAssignmentRow> accepted = rows.stream()
                .filter(row -> event(row, "WORKER_MATCH_ACCEPTED"))
                .toList();
        if (accepted.stream().noneMatch(row -> Boolean.FALSE.equals(row.foreground()))) {
            issues.add(new TraceScenarioIssue("MISSING_BACKGROUND_DECLARATION",
                    "Expected accepted worker match with foreground=false"));
        }
        if (accepted.stream().noneMatch(this::showsBackgroundReservationEvidence)) {
            issues.add(new TraceScenarioIssue("MISSING_BACKGROUND_RESERVATION",
                    "Expected accepted background worker match with selection reservation evidence"));
        }
        if (accepted.stream().noneMatch(row -> contains(row.reason(), "capacity reserved"))) {
            issues.add(new TraceScenarioIssue("MISSING_CAPACITY_RESERVED_REASON",
                    "Expected accepted worker match reason to mention capacity reservation"));
        }

        if (!has(rows, row -> event(row, "ASSIGNMENT_SUMMARY")
                && result(row, "SUCCESS")
                && Boolean.FALSE.equals(row.foreground()))) {
            issues.add(new TraceScenarioIssue("MISSING_BACKGROUND_SUCCESS_ASSIGNMENT_SUMMARY",
                    "Expected ASSIGNMENT_SUMMARY result=SUCCESS with foreground=false"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY")
                && result(row, "SUCCESS")
                && Boolean.FALSE.equals(row.foreground())
                && row.dispatchedMessageCount() != null
                && row.dispatchedMessageCount() > 0)) {
            issues.add(new TraceScenarioIssue("MISSING_BACKGROUND_SUCCESS_BINDING",
                    "Expected DISPATCH_BINDING_SUMMARY result=SUCCESS with foreground=false and dispatched work"));
        }
        if (!has(rows, row -> transition(row, "TASK_STATUS_TRANSITION", "READY", "RUNNING"))) {
            issues.add(new TraceScenarioIssue("MISSING_READY_TO_RUNNING",
                    "Expected task transition READY -> RUNNING"));
        }
    }

    private boolean showsBackgroundReservationEvidence(TraceAssignmentRow row) {
        if (row.workerReservedCount() == null
                || row.workerDeclaredCapacity() == null) {
            return false;
        }
        return row.workerReservedCount() > 0
                && row.workerReservedCount() <= row.workerDeclaredCapacity();
    }

    private boolean contains(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }
}
