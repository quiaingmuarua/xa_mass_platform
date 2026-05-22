package com.xa.mass.testing.soak;

import java.util.ArrayList;
import java.util.List;

final class SoakRuntimeInvariantChecker {

    private SoakRuntimeInvariantChecker() {
    }

    static SoakInvariantReport verify(Snapshot snapshot) {
        List<SoakInvariantIssue> issues = new ArrayList<>();
        requireEquals(issues,
                "TASK_TERMINAL_COUNT_MISMATCH",
                "tasksSubmitted must equal tasksTerminal",
                snapshot.tasksSubmitted(),
                snapshot.tasksTerminal());
        requireEquals(issues,
                "RUNTIME_WORK_COUNT_MISMATCH",
                "runtime total work must equal submitted items",
                snapshot.expectedWorkItems(),
                snapshot.runtimeTotalWorkItems());
        requireEquals(issues,
                "VISIBLE_RESULT_COUNT_MISMATCH",
                "visible results must equal submitted items",
                snapshot.expectedWorkItems(),
                snapshot.visibleResults());
        requireEquals(issues,
                "SUCCESS_COUNT_MISMATCH",
                "runtime success count must match expected failure profile",
                snapshot.expectedSuccessWorkItems(),
                snapshot.runtimeSuccessWorkItems());
        requireEquals(issues,
                "FAILED_COUNT_MISMATCH",
                "runtime failed count must match expected failure profile",
                snapshot.expectedFailedWorkItems(),
                snapshot.runtimeFailedWorkItems());
        requireEquals(issues,
                "ACTIVE_LEASES_NOT_DRAINED",
                "active leases should drain to zero",
                0,
                snapshot.activeLeasesAtEnd());
        if (snapshot.traceEnabled()) {
            if (!snapshot.traceValid()) {
                issues.add(new SoakInvariantIssue(
                        "TRACE_VALIDATION_FAILED",
                        "trace validation should pass"));
            }
            requireEquals(issues,
                    "TRACE_EVENTS_DROPPED",
                    "trace sink should not drop events",
                    0,
                    snapshot.traceDropped());
            if (!snapshot.traceAnalysesOk()) {
                issues.add(new SoakInvariantIssue(
                        "TRACE_ANALYSIS_FAILED",
                        "trace scenario analysis should pass"));
            }
        }
        if (snapshot.requireLateWorkerWork()) {
            requirePositive(issues,
                    "LATE_WORKER_RECEIVED_NO_WORK",
                    "late workers should receive work when requireLateWorkerWork=true",
                    snapshot.lateWorkerReceivedItems());
            requirePositive(issues,
                    "LATE_WORKER_SUBMITTED_NO_RESULTS",
                    "late workers should submit results when requireLateWorkerWork=true",
                    snapshot.lateWorkerResultSubmissions());
        }
        if (snapshot.workerFailureCount() > 0) {
            issues.add(new SoakInvariantIssue(
                    "WORKER_FAILURES_OBSERVED",
                    "worker failures observed: " + snapshot.workerFailureCount()));
        }
        return new SoakInvariantReport(issues.isEmpty(), List.copyOf(issues));
    }

    private static void requireEquals(List<SoakInvariantIssue> issues,
                                      String code,
                                      String message,
                                      long expected,
                                      long actual) {
        if (expected != actual) {
            issues.add(new SoakInvariantIssue(
                    code,
                    message + " expected=" + expected + " actual=" + actual));
        }
    }

    private static void requirePositive(List<SoakInvariantIssue> issues,
                                        String code,
                                        String message,
                                        long actual) {
        if (actual <= 0) {
            issues.add(new SoakInvariantIssue(
                    code,
                    message + " actual=" + actual));
        }
    }

    record Snapshot(long tasksSubmitted,
                    long tasksTerminal,
                    long expectedWorkItems,
                    long runtimeTotalWorkItems,
                    long visibleResults,
                    long expectedSuccessWorkItems,
                    long runtimeSuccessWorkItems,
                    long expectedFailedWorkItems,
                    long runtimeFailedWorkItems,
                    long activeLeasesAtEnd,
                    boolean traceEnabled,
                    boolean traceValid,
                    long traceDropped,
                    boolean traceAnalysesOk,
                    boolean requireLateWorkerWork,
                    long lateWorkerReceivedItems,
                    long lateWorkerResultSubmissions,
                    long workerFailureCount) {
    }
}
