package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeContractShapeTest {

    @Test
    void appendBatchIsAllAcceptedOrRejected() {
        var item = new AppendItemInput("message-1", Map.of("value", 1));
        var accepted = AppendBatchOutcome.allAccepted("task-1", List.of(item.messageId()));

        assertThat(accepted.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(accepted.acceptedMessageIds()).containsExactly("message-1");

        var rejected = AppendBatchOutcome.rejectedBeforeRuntime("task-1", "closed");
        assertThat(rejected.status()).isEqualTo(AppendBatchStatus.REJECTED_BEFORE_RUNTIME);
        assertThat(rejected.acceptedMessageIds()).isEmpty();
    }

    @Test
    void appendCommandDoesNotAcceptOversizedBatch() {
        var commandItems = List.of(
                new AppendItemInput("message-1", Map.of()),
                new AppendItemInput("message-2", Map.of()));

        assertThatThrownBy(() -> new AppendBatchCommand(
                "task-1",
                commandItems,
                new AppendAdmissionPolicy(1, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                RuntimeEpoch.of("task-1", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAppendBatchSize");
    }

    @Test
    void commandValuesDefensivelyCopyCollections() {
        var payload = new HashMap<String, Object>();
        payload.put("value", 1);
        var item = new AppendItemInput("message-1", payload);
        payload.put("value", 2);

        assertThat(item.payloadJson()).containsEntry("value", 1);
        assertThatThrownBy(() -> item.payloadJson().put("new", 3))
                .isInstanceOf(UnsupportedOperationException.class);

        var mutableItems = new ArrayList<AppendItemInput>();
        mutableItems.add(item);
        var command = new AppendBatchCommand("task-1", mutableItems, null, null);
        mutableItems.clear();

        assertThat(command.items()).containsExactly(item);
    }

    @Test
    void claimRequiresWorkerReservationBeforeActiveLeaseCreation() {
        var reservation = new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "delivery-ref");
        var command = new ClaimReadyCommand(
                "task-1",
                List.of(reservation),
                new ClaimLeasePolicy(10, 30_000L, 1L, RuntimeEpoch.of("task-1", 2L)));

        assertThat(command.workerReservations()).containsExactly(reservation);

        assertThatThrownBy(() -> new ClaimReadyCommand("task-1", List.of(), command.leasePolicy()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerReservations");
    }

    @Test
    void messageFinalityOutcomeIsSeparateFromTaskTerminalState() {
        var outcome = MessageFinalityOutcome.logicalFinal("task-1", "message-1", 2, 86_400_000L);

        assertThat(outcome.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(outcome.progressDirty()).isTrue();
        assertThat(outcome.terminalCandidate()).isTrue();
        assertThat(outcome.finalResultExpiresAtMillis()).isEqualTo(86_400_000L);
    }

    @Test
    void finalResultRowCarriesStableWorkerEvidenceAfterActiveLeaseRemoval() {
        var row = new FinalResultRow(
                "task-1",
                "message-1",
                1L,
                1,
                "worker-1",
                "batch-1",
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of(),
                "",
                1_000L,
                86_400_000L);

        assertThat(row.workerId()).isEqualTo("worker-1");
        assertThat(row.batchId()).isEqualTo("batch-1");
        assertThat(row.seq()).isEqualTo(1L);
    }

    @Test
    void progressSnapshotExposesAggregateCountsWithoutTaskShellPolicy() {
        var snapshot = new TaskRuntimeProgressSnapshot("task-1", 0L, 1L, 2L, 3L, 4L, 5L, 6L);

        assertThat(snapshot.totalCount()).isEqualTo(21L);
        assertThat(snapshot.processingCount()).isEqualTo(6L);
        assertThat(snapshot.finalCount()).isEqualTo(15L);
    }

    @Test
    void repairAndReadContractsUseBoundedRequests() {
        var repairCommand = new PollActiveLeaseRepairCommand(100, 1_000L);
        var readRequest = new FinalResultReadRequest("task-1", 0, 50);

        assertThat(repairCommand.limit()).isEqualTo(100);
        assertThat(readRequest.limit()).isEqualTo(50);
        assertThatThrownBy(() -> new PollActiveLeaseRepairCommand(0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }
}
