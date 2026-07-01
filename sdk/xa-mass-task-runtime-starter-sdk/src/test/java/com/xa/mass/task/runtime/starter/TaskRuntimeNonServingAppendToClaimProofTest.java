package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultFinalityPolicySnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RetryPolicySnapshot;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeNonServingAppendToClaimProofTest {

    @Test
    void appendDiscoveryReservationClaimAndResultCanRunThroughNewOwnerWithoutOldPath() {
        try (var handle = TaskRuntimeStarter.start(TaskRuntimeBootstrapConfig.memory(), List.of(), null, () -> 0L)) {
            var runtime = handle.runtime();
            var epoch = RuntimeEpoch.of("task-1", 1L);

            runtime.updateTaskEligibility(new UpdateSchedulerEligibilityCommand(
                    "task-1",
                    new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L),
                    epoch));

            var append = runtime.appendBatch(new AppendBatchCommand(
                    "task-1",
                    List.of(new AppendItemInput("message-1", Map.of("payload", "value"))),
                    new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                    epoch));

            assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

            var discovered = runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, 0L));
            assertThat(discovered.candidates())
                    .extracting(candidate -> candidate.taskId())
                    .containsExactly("task-1");

            var claim = runtime.claimReady(new ClaimReadyCommand(
                    "task-1",
                    List.of(new WorkerReservationEvidence(
                            "worker-1",
                            "worker-group-1",
                            "selected-worker-reservation-1",
                            "adapter-mailbox-ref-1")),
                    new ClaimLeasePolicy(1, 30_000L, 1L, epoch)));

            assertThat(claim.accepted()).isTrue();
            assertThat(claim.claimedItems()).hasSize(1);

            var item = claim.claimedItems().getFirst();
            var result = runtime.applyResult(new ResultApplyCommand(
                    item.taskId(),
                    item.messageId(),
                    item.leaseToken(),
                    item.workerId(),
                    item.attemptNo(),
                    ResultApplySource.WORKER_RESULT,
                    true,
                    Map.of("ok", true),
                    "",
                    new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 1L),
                    new ResultFinalityPolicySnapshot(false, true, 86_400_000L),
                    epoch,
                    1_000L));

            assertThat(result.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
            assertThat(runtime.readFinalResults(new com.xa.mass.task.runtime.FinalResultReadRequest("task-1", 0, 10))
                    .rows())
                    .extracting(row -> row.messageId())
                    .containsExactly("message-1");
        }
    }
}
