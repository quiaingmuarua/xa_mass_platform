package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public abstract class TaskRuntimePortContractTest {

    protected abstract TaskRuntimePorts createRuntime();

    @Test
    void appendAndSchedulerDiscoveryStayDecoupledButDiscoverable() {
        var runtime = createRuntime();
        runtime.updateTaskEligibility(openEligibility("task-1", 1L));

        var append = runtime.appendBatch(new AppendBatchCommand(
                "task-1",
                List.of(new AppendItemInput("message-1", Map.of("value", 1))),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of("task-1", 1L)));

        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var discovered = runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 100L));
        assertThat(discovered.candidates())
                .extracting(SchedulerTaskCandidate::taskId)
                .containsExactly("task-1");
    }

    @Test
    void appendRejectsBacklogOverflowBeforePartialOwnership() {
        var runtime = createRuntime();

        var append = runtime.appendBatch(new AppendBatchCommand(
                "task-1",
                List.of(
                        new AppendItemInput("message-1", Map.of()),
                        new AppendItemInput("message-2", Map.of())),
                new AppendAdmissionPolicy(10, 1),
                RuntimeEpoch.of("task-1", 1L)));

        assertThat(append.status()).isEqualTo(AppendBatchStatus.REJECTED_BEFORE_RUNTIME);
        assertThat(append.acceptedMessageIds()).isEmpty();

        runtime.updateTaskEligibility(openEligibility("task-1", 1L));
        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 100L)).candidates()).isEmpty();
    }

    @Test
    void replayedAcceptedIdentityDoesNotCreateSecondReadyOrFinalTruth() {
        var runtime = createRuntime();
        runtime.updateTaskEligibility(openEligibility("task-1", 1L));
        var command = new AppendBatchCommand(
                "task-1",
                List.of(new AppendItemInput("message-1", Map.of("value", 1))),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of("task-1", 1L));

        assertThat(runtime.appendBatch(command).status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(runtime.appendBatch(command).status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var claim = runtime.claimReady(new ClaimReadyCommand(
                "task-1",
                List.of(
                        new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1"),
                        new WorkerReservationEvidence("worker-2", "group-1", "reservation-2", "target-2")),
                new ClaimLeasePolicy(10, 1_000L, 1L, RuntimeEpoch.of("task-1", 1L))));

        assertThat(claim.claimedItems()).hasSize(1);
        var firstOutcome = runtime.applyResult(resultCommand(claim.claimedItems().getFirst(), true, 500L));
        var duplicateOutcome = runtime.applyResult(resultCommand(claim.claimedItems().getFirst(), true, 501L));

        assertThat(firstOutcome.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(duplicateOutcome.status()).isEqualTo(MessageFinalityStatus.DUPLICATE_OR_LATE);
        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows()).hasSize(1);
    }

    @Test
    void claimRequiresReservationAndCreatesDiscoverableActiveLease() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        var claim = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);

        assertThat(claim.accepted()).isTrue();
        assertThat(claim.claimedItems()).hasSize(1);

        var active = runtime.getActiveWorkForWorker(new ActiveWorkQuery("worker-1", 10));
        assertThat(active.hasActiveWork()).isTrue();
        assertThat(active.activeItems())
                .extracting(ActiveLeaseRepairCandidate::messageId)
                .containsExactly("message-1");

        var activeTask = runtime.getActiveWorkForTask(new ActiveTaskWorkQuery("task-1", 10));
        assertThat(activeTask.activeItems())
                .extracting(
                        ActiveLeaseRepairCandidate::messageId,
                        ActiveLeaseRepairCandidate::workerGroupId,
                        ActiveLeaseRepairCandidate::workerReservationToken)
                .containsExactly(tuple("message-1", "group-1", "reservation-worker-1"));
    }

    @Test
    void claimPreservesWorkerReservationCloseEvidence() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        var claim = runtime.claimReady(new ClaimReadyCommand(
                "task-1",
                List.of(new WorkerReservationEvidence(
                        "worker-1",
                        "group-1",
                        "reservation-1",
                        "target-1",
                        "batch-1",
                        123L)),
                new ClaimLeasePolicy(1, 1_000L, 1L, RuntimeEpoch.of("task-1", 1L))));

        assertThat(claim.claimedItems())
                .extracting(ClaimedWorkItem::workerReservationToken, ClaimedWorkItem::scoreBandClaimScore)
                .containsExactly(tuple("reservation-1", 123L));
        assertThat(runtime.getActiveWorkForTask(new ActiveTaskWorkQuery("task-1", 10)).activeItems())
                .extracting(
                        ActiveLeaseRepairCandidate::workerReservationToken,
                        ActiveLeaseRepairCandidate::scoreBandClaimScore)
                .containsExactly(tuple("reservation-1", 123L));
    }

    @Test
    void claimPreservesHandlerAndPayloadReferenceCarrierFields() {
        var runtime = createRuntime();
        runtime.updateTaskEligibility(openEligibility("task-1", 1L));
        var append = runtime.appendBatch(new AppendBatchCommand(
                "task-1",
                List.of(new AppendItemInput(
                        "message-1",
                        "demo.event",
                        Map.of("value", 1),
                        "payload-ref-1")),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of("task-1", 1L)));
        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var claim = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);

        assertThat(claim.claimedItems()).hasSize(1);
        var item = claim.claimedItems().getFirst();
        assertThat(item.eventCode()).isEqualTo("demo.event");
        assertThat(item.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(((Number) item.payloadJson().get("value")).intValue()).isEqualTo(1);
    }

    @Test
    void claimPreservesDispatchBatchCarrierFromReservationEvidence() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        var claim = runtime.claimReady(new ClaimReadyCommand(
                "task-1",
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1", "batch-1")),
                new ClaimLeasePolicy(1, 1_000L, 1L, RuntimeEpoch.of("task-1", 1L))));

        assertThat(claim.accepted()).isTrue();
        assertThat(claim.claimedItems().getFirst().batchId()).isEqualTo("batch-1");
    }

    @Test
    void claimUsesMaxItemsAsTotalBatchAndReusesReservationsRoundRobin() {
        var runtime = createRuntime();
        runtime.updateTaskEligibility(openEligibility("task-1", 1L));
        var append = runtime.appendBatch(new AppendBatchCommand(
                "task-1",
                List.of(
                        new AppendItemInput("message-1", Map.of()),
                        new AppendItemInput("message-2", Map.of()),
                        new AppendItemInput("message-3", Map.of()),
                        new AppendItemInput("message-4", Map.of()),
                        new AppendItemInput("message-5", Map.of())),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of("task-1", 1L)));
        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var claim = runtime.claimReady(new ClaimReadyCommand(
                "task-1",
                List.of(
                        new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1", "batch-1"),
                        new WorkerReservationEvidence("worker-2", "group-1", "reservation-2", "target-2", "batch-2")),
                new ClaimLeasePolicy(5, 1_000L, 1L, RuntimeEpoch.of("task-1", 1L))));

        assertThat(claim.claimedItems()).hasSize(5);
        assertThat(claim.claimedItems())
                .extracting(ClaimedWorkItem::workerId)
                .containsExactly("worker-1", "worker-2", "worker-1", "worker-2", "worker-1");
        assertThat(claim.claimedItems())
                .extracting(ClaimedWorkItem::batchId)
                .containsExactly("batch-1", "batch-2", "batch-1", "batch-2", "batch-1");
        assertThat(claim.claimedItems())
                .extracting(ClaimedWorkItem::leaseToken)
                .doesNotHaveDuplicates();
        assertThat(runtime.getActiveWorkForWorker(new ActiveWorkQuery("worker-1", 10)).activeItems()).hasSize(3);
        assertThat(runtime.getActiveWorkForWorker(new ActiveWorkQuery("worker-2", 10)).activeItems()).hasSize(2);
    }

    @Test
    void staleEpochClaimDoesNotCreateActiveLease() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 2L);

        var rejected = runtime.claimReady(new ClaimReadyCommand(
                "task-1",
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1")),
                new ClaimLeasePolicy(1, 1_000L, 1L, RuntimeEpoch.of("task-1", 1L))));

        assertThat(rejected.accepted()).isFalse();
        assertThat(runtime.getActiveWorkForWorker(new ActiveWorkQuery("worker-1", 10)).hasActiveWork()).isFalse();
    }

    @Test
    void resultSuccessClosesActiveLeaseAndCreatesFinalReadRow() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();

        var outcome = runtime.applyResult(resultCommand(item, true, 500L));

        assertThat(outcome.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(runtime.getActiveWorkForWorker(new ActiveWorkQuery("worker-1", 10)).hasActiveWork()).isFalse();

        var rows = runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10));
        assertThat(rows.rows())
                .extracting(FinalResultRow::messageId, FinalResultRow::seq, FinalResultRow::workerId)
                .containsExactly(tuple("message-1", 1L, "worker-1"));
        assertThat(rows.nextAfterSeq()).isEqualTo(1L);
        assertThat(rows.hasMore()).isFalse();
        assertThat(rows.totalVisible()).isEqualTo(1L);
        assertThat(runtime.getFinalResultByMessageId("task-1", "message-1"))
                .hasValueSatisfying(row -> {
                    assertThat(row.seq()).isEqualTo(1L);
                    assertThat(row.workerId()).isEqualTo("worker-1");
                });

        var progress = runtime.progressSnapshot("task-1");
        assertThat(progress.totalCount()).isEqualTo(1);
        assertThat(progress.successCount()).isEqualTo(1);
        assertThat(progress.processingCount()).isZero();
    }

    @Test
    void failedResultCanScheduleRetryWithoutLosingItemOwnership() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();

        var outcome = runtime.applyResult(new ResultApplyCommand(
                item.taskId(),
                item.messageId(),
                item.leaseToken(),
                item.workerId(),
                item.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                false,
                Map.of(),
                "failed",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 1, 0L, 1L),
                new ResultFinalityPolicySnapshot(false, true, 86_400_000L),
                RuntimeEpoch.of(item.taskId(), 1L),
                500L));

        assertThat(outcome.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 500L)).candidates())
                .extracting(SchedulerTaskCandidate::taskId)
                .containsExactly("task-1");

        var progress = runtime.progressSnapshot("task-1");
        assertThat(progress.totalCount()).isEqualTo(1);
        assertThat(progress.processingCount()).isEqualTo(1);
        assertThat(progress.finalCount()).isZero();
    }

    @Test
    void progressSnapshotSeparatesWorkerFailuresFromLeaseTimeouts() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-failed", 1L);
        var failed = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(new ResultApplyCommand(
                failed.taskId(),
                failed.messageId(),
                failed.leaseToken(),
                failed.workerId(),
                failed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                false,
                Map.of(),
                "failed",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 1L),
                new ResultFinalityPolicySnapshot(false, true, 86_400_000L),
                RuntimeEpoch.of(failed.taskId(), 1L),
                500L));

        appendOne(runtime, "task-1", "message-expired", 1L);
        var expired = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(new ResultApplyCommand(
                expired.taskId(),
                expired.messageId(),
                expired.leaseToken(),
                expired.workerId(),
                expired.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of(),
                "expired",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 1L),
                new ResultFinalityPolicySnapshot(false, true, 86_400_000L),
                RuntimeEpoch.of(expired.taskId(), 1L),
                600L));

        var progress = runtime.progressSnapshot("task-1");

        assertThat(progress.totalCount()).isEqualTo(2);
        assertThat(progress.failedCount()).isEqualTo(1);
        assertThat(progress.expiredCount()).isEqualTo(1);
        assertThat(progress.finalCount()).isEqualTo(2);
        assertThat(progress.processingCount()).isZero();
    }

    @Test
    void expiredLeasesRemainDiscoverableForRepair() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);
        claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);

        var expired = runtime.pollExpiredActiveLeases(new PollActiveLeaseRepairCommand(10, 1_001L));

        assertThat(expired.candidates())
                .extracting(ActiveLeaseRepairCandidate::messageId)
                .containsExactly("message-1");
    }

    @Test
    void discardRemovesReadyActiveAndFinalRuntimeState() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-ready", 1L);
        appendOne(runtime, "task-1", "message-final", 1L);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(resultCommand(item, true, 500L));

        var discarded = runtime.discardTaskRuntime(new DiscardTaskRuntimeCommand(
                "task-1",
                RuntimeEpoch.of("task-1", 1L),
                "delete"));

        assertThat(discarded.discardedReadyItems()).isGreaterThanOrEqualTo(1);
        assertThat(discarded.discardedFinalResults()).isGreaterThanOrEqualTo(1);
        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows()).isEmpty();
        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 1_000L)).candidates()).isEmpty();
    }

    @Test
    void workOnlyDiscardRemovesReadyActiveAndKeepsFinalRows() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-final", 1L);
        var finalItem = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(resultCommand(finalItem, true, 500L));
        appendOne(runtime, "task-1", "message-active", 1L);
        claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);
        appendOne(runtime, "task-1", "message-ready", 1L);

        var discarded = runtime.discardTaskWork(new DiscardTaskWorkCommand(
                "task-1",
                RuntimeEpoch.of("task-1", 1L),
                "terminate"));

        assertThat(discarded.discardedReadyItems()).isGreaterThanOrEqualTo(1);
        assertThat(discarded.discardedActiveItems()).isGreaterThanOrEqualTo(1);
        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows())
                .extracting(FinalResultRow::messageId, FinalResultRow::workerId)
                .containsExactly(tuple("message-final", "worker-1"));
        assertThat(runtime.progressSnapshot("task-1").readyCount()).isZero();
        assertThat(runtime.progressSnapshot("task-1").activeCount()).isZero();
        assertThat(runtime.progressSnapshot("task-1").finalCount()).isEqualTo(1);
        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 1_000L)).candidates()).isEmpty();
    }

    private static void appendOne(TaskRuntimePorts runtime, String taskId, String messageId, long epoch) {
        runtime.updateTaskEligibility(openEligibility(taskId, epoch));
        var outcome = runtime.appendBatch(new AppendBatchCommand(
                taskId,
                List.of(new AppendItemInput(messageId, Map.of("messageId", messageId))),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of(taskId, epoch)));
        assertThat(outcome.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
    }

    private static ClaimReadyOutcome claimOne(
            TaskRuntimePorts runtime,
            String taskId,
            String workerId,
            long epoch,
            long leaseMillis
    ) {
        return runtime.claimReady(new ClaimReadyCommand(
                taskId,
                List.of(new WorkerReservationEvidence(workerId, "group-1", "reservation-" + workerId, "target-1")),
                new ClaimLeasePolicy(1, leaseMillis, 1L, RuntimeEpoch.of(taskId, epoch))));
    }

    private static ResultApplyCommand resultCommand(ClaimedWorkItem item, boolean success, long observedAtMillis) {
        return new ResultApplyCommand(
                item.taskId(),
                item.messageId(),
                item.leaseToken(),
                item.workerId(),
                item.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                success,
                Map.of("value", item.messageId()),
                "",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 1L),
                new ResultFinalityPolicySnapshot(false, true, 86_400_000L),
                RuntimeEpoch.of(item.taskId(), 1L),
                observedAtMillis);
    }

    private static UpdateSchedulerEligibilityCommand openEligibility(String taskId, long epoch) {
        return new UpdateSchedulerEligibilityCommand(
                taskId,
                new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L),
                RuntimeEpoch.of(taskId, epoch));
    }

    protected interface TaskRuntimePorts extends TaskRuntimeAppendPort,
            TaskRuntimeSchedulerPort,
            TaskRuntimeClaimPort,
            TaskRuntimeResultPort,
            TaskRuntimeRepairPort,
            TaskRuntimeProgressPort,
            TaskRuntimeReadPort,
            TaskRuntimeDiscardPort {
    }
}
