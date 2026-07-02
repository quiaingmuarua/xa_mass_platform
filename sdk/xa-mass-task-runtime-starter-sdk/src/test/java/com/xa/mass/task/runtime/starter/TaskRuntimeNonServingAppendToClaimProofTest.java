package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskScoreV1;
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

            runtime.putRuntimeMeta(TaskRuntimeMetaV1.fromPolicy(
                    "task-1",
                    new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L),
                    epoch));
            runtime.setTaskScore("task-1", "default", epoch, TaskScoreV1.dueAt(0L));

            var append = runtime.appendBacklog(
                    "task-1",
                    List.of(new AppendItemInput("message-1", null, Map.of("payload", "value"), null)),
                    10);

            assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

            var discovered = runtime.discoverSchedulable("default", TaskScoreV1.TIME_SCORE_FLOOR, 10);
            assertThat(discovered.candidates())
                    .extracting(candidate -> candidate.taskId())
                    .containsExactly("task-1");

            var claim = runtime.claimBacklog(
                    discovered.candidates().getFirst(),
                    List.of(new WorkerReservationEvidence(
                            "worker-1",
                            "worker-group-1",
                            "selected-worker-reservation-1",
                            "adapter-mailbox-ref-1")),
                    1,
                    30_000L,
                    0L);

            assertThat(claim.accepted()).isTrue();
            assertThat(claim.claimedItems()).hasSize(1);

            var item = claim.claimedItems().getFirst();
            assertThat(runtime.resultCorrelation(item.taskId(), item.messageId()).present()).isTrue();
        }
    }
}
