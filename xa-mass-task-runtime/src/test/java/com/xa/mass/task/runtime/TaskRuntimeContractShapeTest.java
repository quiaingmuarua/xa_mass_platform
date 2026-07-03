package com.xa.mass.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import com.xa.mass.task.runtime.command.TaskRuntimeCommandPort;

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
    void appendItemInputDefensivelyCopiesPayloads() {
        var payload = new HashMap<String, Object>();
        payload.put("value", 1);
        var frame = new AppendItemInput("message-1", "demo.event", payload, "payload-ref");
        payload.put("value", 2);

        assertThat(frame.payloadJson()).containsEntry("value", 1);
        assertThatThrownBy(() -> frame.payloadJson().put("new", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scoreCandidateCarriesOpaqueRuntimeEvidence() {
        var epoch = RuntimeEpoch.of("task-1", 2L);
        var candidate = new ScoreCandidate("task-1", "lane-1", epoch, TaskScoreV1.dueAt(1_000L));

        assertThat(candidate.taskId()).isEqualTo("task-1");
        assertThat(candidate.laneKey()).isEqualTo("lane-1");
        assertThat(candidate.runtimeEpoch()).isEqualTo(epoch);
        assertThat(candidate.observedScore().score()).isEqualTo(TaskScoreV1.TIME_SCORE_FLOOR);
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
    void runtimeFactAndReadContractsUseBoundedStableValues() {
        var fact = new RuntimeResultFact(
                "task-1",
                "message-1",
                "lease-1",
                "worker-1",
                0,
                null,
                true,
                Map.of(),
                null,
                null,
                -1L);
        var readRequest = new FinalResultReadRequest("task-1", 0, 50);

        assertThat(fact.attemptNo()).isEqualTo(1);
        assertThat(fact.source()).isEqualTo(ResultApplySource.WORKER_RESULT);
        assertThat(fact.observedAtMillis()).isZero();
        assertThat(readRequest.limit()).isEqualTo(50);
    }

    @Test
    void pauseCommandIsEventOnlyAndDoesNotExposeCallerProvidedTime() {
        var pauseMethods = Arrays.stream(TaskRuntimeCommandPort.class.getMethods())
                .filter(method -> method.getName().equals("pause"))
                .toList();

        assertThat(pauseMethods).hasSize(1);
        assertThat(pauseMethods.get(0).getParameterTypes()).containsExactly(String.class);
    }
}
