package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;
import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceQueryFilter;
import com.xa.mass.trace.query.TraceSource;
import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TaskRuntimeExternalWorkerSuccessScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public String id() {
        return "task-runtime-external-worker-success";
    }

    @Override
    public TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                       TraceSource source,
                                       String taskId) throws Exception {
        Target target = parseTarget(taskId);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (target == null) {
            issues.add(new TraceScenarioIssue("INVALID_TASK_RUNTIME_EXTERNAL_WORKER_TARGET",
                    "task-runtime-external-worker-success expects --task-id <taskId>,<workerId>"));
            return report(source, taskId, List.of(), List.of(), issues);
        }

        List<TraceTimelineRow> taskRows = queryBackend.timeline(source, target.taskId(), null, 2_000);
        List<TraceTimelineRow> workerRows = queryBackend.query(
                source,
                new TraceQueryFilter(null, null, target.workerId(), null, null, null),
                2_000
        );
        List<TraceAssignmentRow> assignmentRows = queryBackend.assignment(source, target.taskId(), 2_000);
        if (taskRows.isEmpty()) {
            issues.add(new TraceScenarioIssue("EMPTY_TASK_RUNTIME_TIMELINE",
                    "No task-scoped trace events found for taskId=" + target.taskId()));
        } else {
            analyzeTaskRows(taskRows, target, issues);
            analyzeAssignmentRows(assignmentRows, target, issues);
        }
        return report(source, taskId, taskRows, workerRows, issues);
    }

    private void analyzeTaskRows(List<TraceTimelineRow> rows,
                                 Target target,
                                 List<TraceScenarioIssue> issues) {
        requireEvent(rows, issues, "WORKER_MATCH_ACCEPTED",
                "task-runtime external worker proof requires worker selection evidence");
        requireEvent(rows, issues, "DISPATCH_BINDING_SUMMARY",
                "task-runtime external worker proof requires dispatch binding through the serving lane");
        requireEvent(rows, issues, "TASK_TERMINAL_CLOSED",
                "task-runtime external worker proof requires terminal convergence");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_SUCCEEDED");

        if (rows.stream()
                .filter(row -> "WORKER_MATCH_ACCEPTED".equals(row.eventType()))
                .noneMatch(row -> target.workerId().equals(row.workerId()))) {
            issues.add(new TraceScenarioIssue("MISSING_TASK_RUNTIME_WORKER_MATCH",
                    "Expected WORKER_MATCH_ACCEPTED for workerId=" + target.workerId()));
        }
        if (rows.stream()
                .filter(row -> "TASK_WORK_ATTEMPT_STATUS_TRANSITION".equals(row.eventType()))
                .noneMatch(row -> target.workerId().equals(row.workerId())
                        && "CREATED".equals(row.src())
                        && "LEASED".equals(row.dst()))) {
            issues.add(new TraceScenarioIssue("MISSING_TASK_RUNTIME_ATTEMPT_LEASED",
                    "Expected task-runtime attempt transition CREATED -> LEASED for workerId="
                            + target.workerId()));
        }
        if (rows.stream()
                .filter(row -> "TASK_WORK_ATTEMPT_STATUS_TRANSITION".equals(row.eventType()))
                .noneMatch(row -> target.workerId().equals(row.workerId())
                        && "LEASED".equals(row.src())
                        && "DISPATCHED".equals(row.dst()))) {
            issues.add(new TraceScenarioIssue("MISSING_TASK_RUNTIME_ATTEMPT_DISPATCHED",
                    "Expected task-runtime attempt transition LEASED -> DISPATCHED for workerId="
                            + target.workerId()));
        }

        TraceSequenceVerifier.requireOrdered(
                rows,
                issues,
                "TASK_RUNTIME_EXTERNAL_WORKER_SEQUENCE_MISMATCH",
                "task-runtime external worker success requires dispatch binding before terminal closure",
                TraceSequenceExpectation.event("DISPATCH_BINDING_SUMMARY"),
                TraceSequenceExpectation.event("TASK_TERMINAL_CLOSED")
                        .terminalReason("ALL_MESSAGES_SUCCEEDED"));
    }

    private void analyzeAssignmentRows(List<TraceAssignmentRow> rows,
                                       Target target,
                                       List<TraceScenarioIssue> issues) {
        boolean hasGroupFirstEvidence = rows.stream()
                .filter(row -> "WORKER_MATCH_ACCEPTED".equals(row.eventType()))
                .filter(row -> target.workerId().equals(row.workerId()))
                .anyMatch(row -> present(row.workerGroupId())
                        && present(row.workerCandidateSource())
                        && isGroupFirstCandidateSource(row.workerCandidateSource()));
        boolean hasDispatchEventBindingEvidence = rows.stream()
                .filter(row -> "TASK_WORK_ATTEMPT_STATUS_TRANSITION".equals(row.eventType()))
                .filter(row -> target.workerId().equals(row.workerId()))
                .anyMatch(row -> present(row.workerGroupId())
                        && present(row.eventBindingKey())
                        && present(row.workerCandidateSource())
                        && isGroupFirstCandidateSource(row.workerCandidateSource()));
        if (!hasGroupFirstEvidence || !hasDispatchEventBindingEvidence) {
            issues.add(new TraceScenarioIssue("MISSING_TASK_RUNTIME_DISPATCH_EVIDENCE",
                    "Expected accepted worker evidence plus dispatched attempt eventBindingKey evidence"));
        }
    }

    private static boolean isGroupFirstCandidateSource(String source) {
        return "GROUP_SELECTOR".equals(source)
                || "GROUP_SELECTOR_WITH_NODE".equals(source)
                || "TARGET_WORKER".equals(source);
    }

    private void requireEvent(List<TraceTimelineRow> rows,
                              List<TraceScenarioIssue> issues,
                              String eventType,
                              String message) {
        if (rows.stream().noneMatch(row -> eventType.equals(row.eventType()))) {
            issues.add(new TraceScenarioIssue("MISSING_" + eventType, message));
        }
    }

    private void requireTerminalReason(List<TraceTimelineRow> rows,
                                       List<TraceScenarioIssue> issues,
                                       String expected) {
        boolean matched = rows.stream()
                .filter(row -> "TASK_TERMINAL_CLOSED".equals(row.eventType()))
                .anyMatch(row -> expected.equals(row.terminalReason()));
        if (!matched) {
            issues.add(new TraceScenarioIssue("TASK_RUNTIME_TERMINAL_REASON_MISMATCH",
                    "Expected TASK_TERMINAL_CLOSED terminalReason=" + expected));
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private TraceScenarioReport report(TraceSource source,
                                       String target,
                                       List<TraceTimelineRow> taskRows,
                                       List<TraceTimelineRow> workerRows,
                                       List<TraceScenarioIssue> issues) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TraceTimelineRow row : taskRows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        for (TraceTimelineRow row : workerRows) {
            counts.merge(row.eventType(), 1L, Long::sum);
        }
        return new TraceScenarioReport(
                id(),
                target,
                source.inputPath().toString(),
                issues.isEmpty(),
                taskRows.size() + workerRows.size(),
                Map.copyOf(counts),
                List.copyOf(issues)
        );
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

    private record Target(String taskId, String workerId) {
    }
}
