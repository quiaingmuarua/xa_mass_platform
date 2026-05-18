package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceAssignmentRow;

import java.util.List;
import java.util.Map;

final class WorkerResourceCleanupWithoutContextScenarioAnalyzer extends AbstractAssignmentScenarioAnalyzer {

    @Override
    public String id() {
        return "worker-resource-cleanup-without-context";
    }

    @Override
    protected void analyzeAssignment(List<TraceAssignmentRow> rows,
                                     Map<String, Long> counts,
                                     List<TraceScenarioIssue> issues) {
        requireEvent(counts, issues, "WORKER_MATCH_ACCEPTED",
                "worker-resource-cleanup-without-context requires accepted stateless worker match evidence");
        requireEvent(counts, issues, "DISPATCH_BINDING_SUMMARY",
                "worker-resource-cleanup-without-context requires dispatch binding evidence");
        requireEvent(counts, issues, "TASK_WORK_ATTEMPT_CLOSED",
                "worker-resource-cleanup-without-context requires canonical attempt close evidence");
        requireEvent(counts, issues, "WORKER_LOCK_RELEASED",
                "worker-resource-cleanup-without-context requires worker lock release evidence");
        requireEvent(counts, issues, "RESOURCE_RELEASED",
                "worker-resource-cleanup-without-context requires worker resource release evidence");

        if (!has(rows, row -> event(row, "WORKER_MATCH_ACCEPTED") && statelessWorker(row))) {
            issues.add(new TraceScenarioIssue("MISSING_STATELESS_ACCEPTED_WORKER",
                    "Expected WORKER_MATCH_ACCEPTED with workerId and without workerContextId"));
        }
        if (!has(rows, row -> event(row, "DISPATCH_BINDING_SUMMARY")
                && result(row, "SUCCESS")
                && row.dispatchedMessageCount() != null
                && row.dispatchedMessageCount() > 0
                && row.uniqueWorkerCount() != null
                && row.uniqueWorkerCount() > 0)) {
            issues.add(new TraceScenarioIssue("MISSING_STATELESS_SUCCESS_BINDING",
                    "Expected successful DISPATCH_BINDING_SUMMARY with dispatched work and worker-level binding evidence"));
        }
        if (!has(rows, row -> event(row, "TASK_WORK_ATTEMPT_CLOSED")
                && result(row, "SUCCESS")
                && statelessWorker(row))) {
            issues.add(new TraceScenarioIssue("MISSING_SUCCESS_ATTEMPT_CLOSE",
                    "Expected successful TASK_WORK_ATTEMPT_CLOSED with workerId and without workerContextId before cleanup"));
        }
        if (!has(rows, row -> event(row, "WORKER_LOCK_RELEASED") && statelessWorker(row))) {
            issues.add(new TraceScenarioIssue("MISSING_STATELESS_WORKER_LOCK_RELEASE",
                    "Expected WORKER_LOCK_RELEASED with workerId and without workerContextId"));
        }
        if (!has(rows, row -> event(row, "RESOURCE_RELEASED") && statelessWorker(row))) {
            issues.add(new TraceScenarioIssue("MISSING_STATELESS_RESOURCE_RELEASE",
                    "Expected RESOURCE_RELEASED with workerId and without workerContextId"));
        }
        if (has(rows, row -> event(row, "RESOURCE_RELEASED") && present(row.workerContextId()))) {
            issues.add(new TraceScenarioIssue("RESOURCE_RELEASE_DEPENDS_ON_WORKER_CONTEXT",
                    "Worker cleanup proof must not depend on RESOURCE_RELEASED rows carrying workerContextId"));
        }
        if (counts.getOrDefault("WORKER_CONTEXT_STATUS_TRANSITION", 0L) > 0L) {
            issues.add(new TraceScenarioIssue("WORKER_CONTEXT_LIFECYCLE_OBSERVED",
                    "Stateless worker cleanup proof must not require WorkerContext lifecycle transitions"));
        }
    }

    private boolean statelessWorker(TraceAssignmentRow row) {
        return row != null && present(row.workerId()) && !present(row.workerContextId());
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
