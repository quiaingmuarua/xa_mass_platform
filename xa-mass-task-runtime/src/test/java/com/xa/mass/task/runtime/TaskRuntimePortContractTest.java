package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public abstract class TaskRuntimePortContractTest {

    private static final String LANE = "default";
    private static final long DUE = TaskScoreV1.TIME_SCORE_FLOOR;

    protected abstract TaskRuntimePorts createRuntime();

    @Test
    void appendAndScoreDiscoveryStayDecoupledButDiscoverable() {
        var runtime = createRuntime();
        runtime.putRuntimeMeta(openMeta("task-1", 1L));

        var append = runtime.appendBacklog("task-1", List.of(frame("message-1", Map.of("value", 1))), 10);

        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates())
                .extracting(ScoreCandidate::taskId)
                .containsExactly("task-1");
    }

    @Test
    void appendWithoutScoreDoesNotCreateSchedulableCandidate() {
        var runtime = createRuntime();

        var append = runtime.appendBacklog("task-1", List.of(frame("message-1", Map.of("value", 1))), 10);

        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(runtime.taskScore("task-1", LANE)).isEmpty();
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
    }

    @Test
    void appendRejectsOversizedBatchBeforePartialOwnership() {
        var runtime = createRuntime();

        var append = runtime.appendBacklog("task-1", List.of(
                frame("message-1", Map.of()),
                frame("message-2", Map.of())), 1);

        assertThat(append.status()).isEqualTo(AppendBatchStatus.REJECTED_BEFORE_RUNTIME);
        assertThat(append.acceptedMessageIds()).isEmpty();

        runtime.putRuntimeMeta(openMeta("task-1", 1L));
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
    }

    @Test
    void claimRequiresReservationAndCreatesDiscoverableActiveLease() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        var claim = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);

        assertThat(claim.accepted()).isTrue();
        assertThat(claim.claimedItems()).hasSize(1);
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems())
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

        var claim = runtime.claimBacklog(
                runtime.scoreCandidate("task-1", LANE).orElseThrow(),
                List.of(new WorkerReservationEvidence(
                        "worker-1",
                        "group-1",
                        "reservation-1",
                        "target-1",
                        "batch-1",
                        123L)),
                1,
                1_000L,
                0L);

        assertThat(claim.claimedItems())
                .extracting(ClaimedWorkItem::workerReservationToken, ClaimedWorkItem::scoreBandClaimScore)
                .containsExactly(tuple("reservation-1", 123L));
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems())
                .extracting(
                        ActiveLeaseRepairCandidate::workerReservationToken,
                        ActiveLeaseRepairCandidate::scoreBandClaimScore)
                .containsExactly(tuple("reservation-1", 123L));
    }

    @Test
    void claimPreservesHandlerAndPayloadReferenceCarrierFields() {
        var runtime = createRuntime();
        runtime.putRuntimeMeta(openMeta("task-1", 1L));
        var append = runtime.appendBacklog("task-1", List.of(new AppendItemInput(
                "message-1",
                "demo.event",
                Map.of("value", 1),
                "payload-ref-1")), 10);
        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var claim = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);

        assertThat(claim.claimedItems()).hasSize(1);
        var item = claim.claimedItems().getFirst();
        assertThat(item.eventCode()).isEqualTo("demo.event");
        assertThat(item.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(((Number) item.payloadJson().get("value")).intValue()).isEqualTo(1);
    }

    @Test
    void claimUsesMaxItemsAsTotalBatchAndReusesReservationsRoundRobin() {
        var runtime = createRuntime();
        runtime.putRuntimeMeta(openMeta("task-1", 1L));
        var append = runtime.appendBacklog("task-1", List.of(
                frame("message-1", Map.of()),
                frame("message-2", Map.of()),
                frame("message-3", Map.of()),
                frame("message-4", Map.of()),
                frame("message-5", Map.of())), 10);
        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var claim = runtime.claimBacklog(
                runtime.scoreCandidate("task-1", LANE).orElseThrow(),
                List.of(
                        new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1", "batch-1"),
                        new WorkerReservationEvidence("worker-2", "group-1", "reservation-2", "target-2", "batch-2")),
                5,
                1_000L,
                0L);

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
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems()).hasSize(5);
    }

    @Test
    void staleEpochClaimDoesNotCreateActiveLease() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 2L);

        var rejected = runtime.claimBacklog(
                new ScoreCandidate("task-1", LANE, RuntimeEpoch.of("task-1", 1L), TaskScoreV1.dueAt(0L)),
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1")),
                1,
                1_000L,
                0L);

        assertThat(rejected.accepted()).isFalse();
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems()).isEmpty();
    }

    @Test
    void maintenanceScoreCandidateDoesNotClaimBacklog() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        var rejected = runtime.claimBacklog(
                new ScoreCandidate("task-1", LANE, RuntimeEpoch.of("task-1", 1L), TaskScoreV1.maintActive()),
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1")),
                1,
                1_000L,
                0L);

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.rejectionReason()).contains("dispatch-visible");
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems()).isEmpty();
    }

    @Test
    void resultSuccessClosesActiveLeaseAndCreatesFinalReadRow() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();

        var outcome = runtime.applyResult(resultFact(item, true, 500L));

        assertThat(outcome.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(runtime.activeWorkForTask("task-1", 10).activeItems()).isEmpty();

        var rows = runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10));
        assertThat(rows.rows())
                .extracting(FinalResultRow::messageId, FinalResultRow::seq, FinalResultRow::workerId)
                .containsExactly(tuple("message-1", 1L, "worker-1"));
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
        runtime.putRuntimeMeta(openMeta("task-1", 1L, retryPolicy(1)));
        assertThat(runtime.appendBacklog("task-1", List.of(frame("message-1", Map.of())), 10).status())
                .isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();

        var outcome = runtime.applyResult(resultFact(item, false, 500L));

        assertThat(outcome.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates())
                .extracting(ScoreCandidate::taskId)
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
        runtime.applyResult(resultFact(failed, ResultApplySource.WORKER_RESULT, false, 500L));

        appendOne(runtime, "task-1", "message-expired", 1L);
        var expired = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(resultFact(expired, ResultApplySource.LEASE_TIMEOUT, false, 600L));

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

        var expired = runtime.scanExpiredLeases(LANE, 1_001L, 10, 10);

        assertThat(expired)
                .extracting(ActiveLeaseRepairCandidate::messageId)
                .containsExactly("message-1");
    }

    @Test
    void closeIfDrainedClosesScoreOnlyAfterMutableWorkIsGone() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-1", 1L);

        assertThat(runtime.closeIfDrained("task-1", LANE, RuntimeEpoch.of("task-1", 1L))).isFalse();

        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        assertThat(runtime.closeIfDrained("task-1", LANE, RuntimeEpoch.of("task-1", 1L))).isFalse();

        runtime.applyResult(resultFact(item, true, 500L));

        assertThat(runtime.closeIfDrained("task-1", LANE, RuntimeEpoch.of("task-1", 1L))).isTrue();
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows())
                .extracting(FinalResultRow::messageId, FinalResultRow::workerId)
                .containsExactly(tuple("message-1", "worker-1"));
    }

    @Test
    void discardRemovesBacklogActiveAndFinalRuntimeState() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-ready", 1L);
        appendOne(runtime, "task-1", "message-final", 1L);
        var item = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(resultFact(item, true, 500L));

        runtime.discardRuntime("task-1", LANE, RuntimeEpoch.of("task-1", 1L), "delete");

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows()).isEmpty();
        assertThat(runtime.progressSnapshot("task-1").totalCount()).isZero();
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
    }

    @Test
    void workOnlyDiscardRemovesBacklogActiveAndKeepsFinalRows() {
        var runtime = createRuntime();
        appendOne(runtime, "task-1", "message-final", 1L);
        var finalItem = claimOne(runtime, "task-1", "worker-1", 1L, 1_000L).claimedItems().getFirst();
        runtime.applyResult(resultFact(finalItem, true, 500L));
        appendOne(runtime, "task-1", "message-active", 1L);
        claimOne(runtime, "task-1", "worker-1", 1L, 1_000L);
        appendOne(runtime, "task-1", "message-ready", 1L);

        runtime.discardWork("task-1", RuntimeEpoch.of("task-1", 1L), "terminate");

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-1", 0, 10)).rows())
                .extracting(FinalResultRow::messageId, FinalResultRow::workerId)
                .containsExactly(tuple("message-final", "worker-1"));
        assertThat(runtime.progressSnapshot("task-1").readyCount()).isZero();
        assertThat(runtime.progressSnapshot("task-1").activeCount()).isZero();
        assertThat(runtime.progressSnapshot("task-1").finalCount()).isEqualTo(1);
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
    }

    private static void appendOne(TaskRuntimePorts runtime, String taskId, String messageId, long epoch) {
        runtime.putRuntimeMeta(openMeta(taskId, epoch));
        var outcome = runtime.appendBacklog(taskId, List.of(frame(messageId, Map.of("messageId", messageId))), 10);
        assertThat(outcome.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
    }

    private static ClaimReadyOutcome claimOne(
            TaskRuntimePorts runtime,
            String taskId,
            String workerId,
            long epoch,
            long leaseMillis
    ) {
        return runtime.claimBacklog(
                runtime.scoreCandidate(taskId, LANE).orElseThrow(),
                List.of(new WorkerReservationEvidence(workerId, "group-1", "reservation-" + workerId, "target-1")),
                1,
                leaseMillis,
                0L);
    }

    private static RuntimeResultFact resultFact(ClaimedWorkItem item, boolean success, long observedAtMillis) {
        return resultFact(item, ResultApplySource.WORKER_RESULT, success, observedAtMillis);
    }

    private static RuntimeResultFact resultFact(ClaimedWorkItem item,
                                                ResultApplySource source,
                                                boolean success,
                                                long observedAtMillis) {
        return new RuntimeResultFact(
                item.taskId(),
                item.messageId(),
                item.leaseToken(),
                item.workerId(),
                item.attemptNo(),
                source,
                success,
                Map.of("value", item.messageId()),
                success ? "" : "failed",
                RuntimeEpoch.of(item.taskId(), 1L),
                observedAtMillis);
    }

    private static AppendItemInput frame(String messageId, Map<String, Object> payload) {
        return new AppendItemInput(messageId, "", payload, null);
    }

    private static TaskRuntimeMetaV1 openMeta(String taskId, long epoch) {
        return openMeta(taskId, epoch, TaskRuntimeResultPolicyV1.defaultPolicy());
    }

    private static TaskRuntimeMetaV1 openMeta(String taskId, long epoch, TaskRuntimeResultPolicyV1 resultPolicy) {
        return new TaskRuntimeMetaV1(
                taskId,
                LANE,
                RuntimeGate.OPEN,
                RuntimeEpoch.of(taskId, epoch),
                DUE,
                0L,
                0L,
                0L,
                resultPolicy);
    }

    private static TaskRuntimeResultPolicyV1 retryPolicy(int maxRetryCount) {
        return new TaskRuntimeResultPolicyV1(
                RetryMode.FAST_READY,
                maxRetryCount,
                0L,
                1L,
                false,
                true,
                86_400_000L);
    }

    protected interface TaskRuntimePorts extends TaskRuntimeWorkPort,
            TaskRuntimeScorePort,
            TaskRuntimeConvergencePort,
            TaskRuntimeReadPort,
            TaskRuntimeResultWindowReadModel {
    }
}
