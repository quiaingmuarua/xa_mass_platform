package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceQueryFilter;
import com.xa.mass.trace.query.TraceSource;
import com.xa.mass.trace.query.TraceAssignmentRow;
import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExternalWorkerPublicContractSuccessScenarioAnalyzer implements TraceScenarioAnalyzer {

    @Override
    public String id() {
        return "external-worker-public-contract-success";
    }

    @Override
    public TraceScenarioReport analyze(TraceQueryBackend queryBackend,
                                       TraceSource source,
                                       String taskId) throws Exception {
        Target target = parseTarget(taskId);
        List<TraceScenarioIssue> issues = new ArrayList<>();
        if (target == null) {
            issues.add(new TraceScenarioIssue("INVALID_EXTERNAL_WORKER_PUBLIC_CONTRACT_TARGET",
                    "external-worker-public-contract-success expects --task-id <taskId>,<workerId>"));
            return report(source, taskId, List.of(), List.of(), issues);
        }

        List<TraceTimelineRow> taskRows = queryBackend.timeline(source, target.taskId(), null, 2_000);
        List<TraceTimelineRow> workerRows = queryBackend.query(
                source,
                new TraceQueryFilter(null, null, target.workerId(), null, null, null),
                2_000
        );
        if (taskRows.isEmpty()) {
            issues.add(new TraceScenarioIssue("EMPTY_TASK_TIMELINE",
                    "No task-scoped trace events found for taskId=" + target.taskId()));
        } else {
            analyzeTaskRows(taskRows, target, issues);
        }
        analyzeAssignmentRows(queryBackend.assignment(source, target.taskId(), 2_000), target, issues);
        analyzeWorkerRows(workerRows, target, issues);
        return report(source, taskId, taskRows, workerRows, issues);
    }

    private void analyzeTaskRows(List<TraceTimelineRow> rows,
                                 Target target,
                                 List<TraceScenarioIssue> issues) {
        requireEvent(rows, issues, "DISPATCH_BINDING_SUMMARY",
                "external-worker-public-contract-success requires dispatch binding through the public worker contract");
        requireEvent(rows, issues, "CALLBACK_ACCEPTED",
                "external-worker-public-contract-success requires callback acceptance from the external worker");
        requireEvent(rows, issues, "TASK_TERMINAL_CLOSED",
                "external-worker-public-contract-success requires terminal convergence");
        requireTerminalReason(rows, issues, "ALL_MESSAGES_SUCCEEDED");

        boolean workerScopedMatchAccepted = rows.stream()
                .filter(row -> "WORKER_MATCH_ACCEPTED".equals(row.eventType()))
                .anyMatch(row -> target.workerId().equals(row.workerId()));
        if (!workerScopedMatchAccepted) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_WORKER_MATCH_ACCEPTED",
                    "Expected WORKER_MATCH_ACCEPTED for workerId=" + target.workerId()));
        }

        boolean workerScopedCallbackAccepted = rows.stream()
                .filter(row -> "CALLBACK_ACCEPTED".equals(row.eventType()))
                .anyMatch(row -> target.workerId().equals(row.workerId()));
        if (!workerScopedCallbackAccepted) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_WORKER_CALLBACK_ACCEPTED",
                    "Expected CALLBACK_ACCEPTED for workerId=" + target.workerId()));
        }

        TraceSequenceVerifier.requireOrdered(
                rows,
                issues,
                "EXTERNAL_WORKER_PUBLIC_CONTRACT_SEQUENCE_MISMATCH",
                "external-worker-public-contract-success requires dispatch binding before callback acceptance and terminal closure",
                TraceSequenceExpectation.event("DISPATCH_BINDING_SUMMARY"),
                TraceSequenceExpectation.event("CALLBACK_ACCEPTED"),
                TraceSequenceExpectation.event("TASK_TERMINAL_CLOSED")
                        .terminalReason("ALL_MESSAGES_SUCCEEDED"));

        rejectEvent(rows, issues, "CALLBACK_IGNORED_DUPLICATE",
                "external-worker-public-contract-success should not rely on duplicate callback suppression");
        rejectEvent(rows, issues, "CALLBACK_IGNORED_LATE",
                "external-worker-public-contract-success should not rely on late callback suppression");
        rejectEvent(rows, issues, "CALLBACK_REJECTED_INVALID_STATE",
                "external-worker-public-contract-success should not reject the callback state");
    }

    private void analyzeAssignmentRows(List<TraceAssignmentRow> rows,
                                       Target target,
                                       List<TraceScenarioIssue> issues) {
        boolean hasGroupFirstEvidence = rows.stream()
                .filter(row -> "WORKER_MATCH_ACCEPTED".equals(row.eventType()))
                .filter(row -> target.workerId().equals(row.workerId()))
                .anyMatch(row -> present(row.workerGroupId())
                        && present(row.adapterNodeId())
                        && present(row.workerCandidateSource())
                        && isGroupFirstCandidateSource(row.workerCandidateSource()));
        boolean hasDispatchEventBindingEvidence = rows.stream()
                .filter(row -> "TASK_WORK_ATTEMPT_STATUS_TRANSITION".equals(row.eventType()))
                .filter(row -> target.workerId().equals(row.workerId()))
                .anyMatch(row -> present(row.workerGroupId())
                        && present(row.adapterNodeId())
                        && present(row.eventBindingKey())
                        && present(row.workerCandidateSource())
                        && isGroupFirstCandidateSource(row.workerCandidateSource()));
        if (!hasGroupFirstEvidence || !hasDispatchEventBindingEvidence) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_GROUP_FIRST_DISPATCH_EVIDENCE",
                    "Expected accepted worker match evidence plus dispatched attempt eventBindingKey evidence"));
        }
    }

    private void analyzeWorkerRows(List<TraceTimelineRow> rows,
                                   Target target,
                                   List<TraceScenarioIssue> issues) {
        boolean hasOnline = rows.stream()
                .anyMatch(row -> "WORKER_ONLINE".equals(row.eventType())
                        && target.workerId().equals(row.workerId()));
        boolean hasControlReport = rows.stream()
                .anyMatch(row -> ("WORKER_STATE_REPORT_APPLIED".equals(row.eventType())
                        || "WORKER_CAPABILITY_REPORT_APPLIED".equals(row.eventType()))
                        && target.workerId().equals(row.workerId()));
        if (!hasOnline) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_WORKER_ONLINE",
                    "Expected WORKER_ONLINE for workerId=" + target.workerId()));
        }
        if (!hasOnline && !hasControlReport) {
            issues.add(new TraceScenarioIssue(
                    "MISSING_WORKER_CONTRACT_EVIDENCE",
                    "Expected worker presence or worker-control trace evidence for workerId=" + target.workerId()));
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

    private void rejectEvent(List<TraceTimelineRow> rows,
                             List<TraceScenarioIssue> issues,
                             String eventType,
                             String message) {
        long count = rows.stream().filter(row -> eventType.equals(row.eventType())).count();
        if (count > 0L) {
            issues.add(new TraceScenarioIssue("UNEXPECTED_" + eventType, message + " (observed " + count + ")"));
        }
    }

    private void requireTerminalReason(List<TraceTimelineRow> rows,
                                       List<TraceScenarioIssue> issues,
                                       String expected) {
        boolean matched = rows.stream()
                .filter(row -> "TASK_TERMINAL_CLOSED".equals(row.eventType()))
                .anyMatch(row -> expected.equals(row.terminalReason()));
        if (!matched) {
            issues.add(new TraceScenarioIssue(
                    "TERMINAL_REASON_MISMATCH",
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
