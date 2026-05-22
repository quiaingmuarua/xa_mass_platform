package com.xa.mass.testing.chaos.support;

import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.trace.sink.ExecutionEventType;

/**
 * System-proof assertions shared by chaos scenarios.
 *
 * <p>This helper owns only recurring proof invariants. Scenario setup, worker
 * behavior, and runner-specific evidence stay in the runner so test intent
 * remains dense and visible.</p>
 */
public final class ChaosProofAssertions {

    private ChaosProofAssertions() {
    }

    public static TerminalRuntimeProof requireSuccessfulTerminalRuntime(
            ChaosRuntimeHarness runtime,
            String taskId,
            String messageId,
            int expectedRetryCount,
            int timeoutSeconds,
            String proofName) throws Exception {
        TaskOutcomeSnapshot outcome = runtime.waitForTerminalTask(
                taskId,
                1,
                timeoutSeconds,
                proofName + ": task must converge"
        );
        TaskWorkStats finalStats = runtime.waitForRuntimeStats(
                taskId,
                1,
                1,
                0,
                0,
                timeoutSeconds,
                proofName + ": runtime should finalize the work item as success"
        );
        ChaosSupport.require(finalStats.readyCount() == 0, proofName + ": runtime ready queue should be drained");
        ChaosSupport.require(finalStats.inflightCount() == 0, proofName + ": runtime leases should be drained");
        ChaosSupport.require(runtime.activeLeases(taskId).isEmpty(), proofName + ": active leases should be empty");
        RecentFinalWorkReceipt finalReceipt = runtime.recentFinalReceipt(taskId, messageId).orElse(null);
        ChaosSupport.require(finalReceipt != null, proofName + ": runtime recent final receipt should exist");
        ChaosSupport.require(finalReceipt.retryCount() == expectedRetryCount,
                proofName + ": final receipt retry count expected " + expectedRetryCount
                        + " but was " + finalReceipt.retryCount());
        ChaosSupport.require("TERMINAL".equals(outcome.status()), proofName + ": task should be TERMINAL");
        ChaosSupport.require("ALL_MESSAGES_SUCCEEDED".equals(outcome.terminalReason()),
                proofName + ": terminal reason should be ALL_MESSAGES_SUCCEEDED");
        return new TerminalRuntimeProof(outcome, finalStats, finalReceipt);
    }

    public static TaskOutcomeSnapshot requireLateReplayDoesNotMutateTerminal(
            ChaosRuntimeHarness runtime,
            String taskId,
            String messageId,
            TaskWorkStats beforeStats,
            RecentFinalWorkReceipt beforeReceipt,
            String proofName) {
        TaskOutcomeSnapshot afterReplayOutcome = runtime.snapshotTaskOutcome(taskId, 1);
        TaskWorkStats afterReplayStats = runtime.runtimeStats(taskId);
        ChaosSupport.require(afterReplayStats.totalCount() == beforeStats.totalCount(),
                proofName + ": late replay must not change runtime total count");
        ChaosSupport.require(afterReplayStats.successCount() == beforeStats.successCount(),
                proofName + ": late replay must not change runtime success count");
        ChaosSupport.require(afterReplayStats.failedCount() == beforeStats.failedCount(),
                proofName + ": late replay must not change runtime failed count");
        ChaosSupport.require(afterReplayStats.expiredCount() == beforeStats.expiredCount(),
                proofName + ": late replay must not change runtime expired count");
        ChaosSupport.require(runtime.activeLeases(taskId).isEmpty(),
                proofName + ": late replay must not create active leases");
        RecentFinalWorkReceipt afterReplayReceipt = runtime.recentFinalReceipt(taskId, messageId).orElse(null);
        ChaosSupport.require(afterReplayReceipt != null, proofName + ": final receipt should still exist after replay");
        ChaosSupport.require(afterReplayReceipt.retryCount() == beforeReceipt.retryCount(),
                proofName + ": late replay must not change final receipt retry count");
        TaskStateSnapshot currentTaskState = runtime.app().getTaskState(taskId);
        ChaosSupport.require(currentTaskState != null && "TERMINAL".equals(currentTaskState.getStatus()),
                proofName + ": late replay must not reopen the task");
        ChaosSupport.require("ALL_MESSAGES_SUCCEEDED".equals(afterReplayOutcome.terminalReason()),
                proofName + ": late replay must not change task terminal reason");
        return afterReplayOutcome;
    }

    public static void requireLeaseExpirySuccessTrace(CapturingExecutionEventSink sink, String taskId) {
        TraceEventAssertions.of(sink)
                .forTask(taskId)
                .requireMinTotalEvents(5)
                .requireEventType(ExecutionEventType.LEASE_EXPIRED)
                .requireEventType(ExecutionEventType.TASK_WORK_RETRY_RESET)
                .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED)
                .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                .requireTerminalReason("ALL_MESSAGES_SUCCEEDED");
    }

    public static void requireLateReplayTrace(CapturingExecutionEventSink sink, String taskId) {
        TraceEventAssertions.of(sink)
                .forTask(taskId)
                .requireMinTotalEvents(5)
                .requireEventType(ExecutionEventType.LEASE_EXPIRED)
                .requireEventType(ExecutionEventType.TASK_WORK_RETRY_RESET)
                .requireEventType(ExecutionEventType.CALLBACK_ACCEPTED)
                .requireAnyEventType(
                        ExecutionEventType.CALLBACK_IGNORED_LATE,
                        ExecutionEventType.CALLBACK_IGNORED_DUPLICATE
                )
                .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
                .requireTerminalReason("ALL_MESSAGES_SUCCEEDED");
    }

    public record TerminalRuntimeProof(TaskOutcomeSnapshot outcome,
                                       TaskWorkStats finalStats,
                                       RecentFinalWorkReceipt finalReceipt) {
    }
}
