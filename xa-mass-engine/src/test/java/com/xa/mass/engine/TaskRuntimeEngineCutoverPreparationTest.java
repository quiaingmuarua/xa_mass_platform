package com.xa.mass.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.engine.runtime.TaskRuntimeDispatchBindingMapper;
import com.xa.mass.engine.runtime.TaskRuntimePolicySnapshotMapper;
import com.xa.mass.engine.runtime.TaskRuntimeResultDecisionMapper;
import com.xa.mass.engine.runtime.TaskRuntimeResultFactMapper;
import com.xa.mass.engine.runtime.TaskRuntimeWorkerReservationMapper;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeEngineCutoverPreparationTest {

    private static final long DUE = TaskScoreV1.TIME_SCORE_FLOOR;

    @Test
    void engineMappersCanDriveAppendClaimDispatchBindingResultProgressWithoutOldRuntimeDtos() {
        var runtime = new InMemoryTaskRuntime(() -> 1_000L);
        var task = new Task(
                "task-1",
                "task",
                "demoApp",
                1,
                Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"),
                UserRef.of("agent"));
        var epoch = RuntimeEpoch.of("task-1", 1L);
        var policy = ResolvedTaskSchedulingPolicy.from(task, null);
        runtime.putRuntimeMeta(new TaskRuntimeMetaV1(
                "task-1",
                "default",
                RuntimeGate.OPEN,
                epoch,
                DUE,
                0L,
                0L,
                0L,
                resultPolicy(policy)));
        var ingressItem = RuntimeTaskIngressItem.fromInput(
                "task-1",
                "message-1",
                Map.of(
                        "eventCode", "demo.event",
                        "payloadRef", "payload-ref-1",
                        "value", 1),
                1);

        var append = runtime.appendBacklog(
                "task-1",
                TaskRuntimeAppendItemMapper.toAppendItems(List.of(ingressItem)),
                10);

        assertThat(append.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        assertThat(runtime.discoverSchedulable("default", DUE, 10).candidates())
                .extracting(candidate -> candidate.taskId())
                .containsExactly("task-1");

        var selectedWorker = SelectedWorkerHandle.of("worker-1", "group-1", "scope-1", true);
        var claimPolicy = TaskRuntimePolicySnapshotMapper.toClaimLeasePolicy(task, policy, 1, 30L);
        var claim = runtime.claimBacklog(
                runtime.scoreCandidate("task-1", "default").orElseThrow(),
                List.of(TaskRuntimeWorkerReservationMapper.toReservationEvidence(selectedWorker, "batch-1")),
                claimPolicy.maxItems(),
                claimPolicy.leaseMillis(),
                1L);

        assertThat(claim.accepted()).isTrue();
        var claimed = claim.claimedItems().getFirst();
        assertThat(claimed.eventCode()).isEqualTo("demo.event");
        assertThat(claimed.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(claimed.workerReservationToken()).isEqualTo(selectedWorker.selectionToken());
        assertThat(claimed.batchId()).isEqualTo("batch-1");
        var dispatchBinding = TaskRuntimeDispatchBindingMapper.fromTaskRuntimeClaim(task, claimed);
        assertThat(dispatchBinding.messageId()).isEqualTo("message-1");
        assertThat(dispatchBinding.eventCode()).isEqualTo("demo.event");
        assertThat(dispatchBinding.payload()).containsEntry("value", 1);
        assertThat(dispatchBinding.workerId()).isEqualTo("worker-1");
        assertThat(dispatchBinding.workerGroupId()).isEqualTo("group-1");
        assertThat(dispatchBinding.selectionToken()).isEqualTo(selectedWorker.selectionToken());
        assertThat(dispatchBinding.eventBindingKey()).isEqualTo("demoApp:demo.event");

        var result = runtime.applyResult(new RuntimeResultFact(
                claimed.taskId(),
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                1_500L));
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(result);
        TaskRuntimeProgressSnapshot stats = runtime.progressSnapshot("task-1");

        assertThat(decision.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(decision.progressDirty()).isTrue();
        assertThat(decision.terminalCandidate()).isTrue();
        assertThat(stats.totalCount()).isEqualTo(1L);
        assertThat(stats.successCount()).isEqualTo(1L);
        assertThat(stats.processingCount()).isZero();
    }

    @Test
    void engineMappersCanApplyDispatchSubmitFailureWithoutOldRuntimeDtos() {
        var runtime = new InMemoryTaskRuntime(() -> 1_000L);
        var task = new Task(
                "task-1",
                "task",
                "demoApp",
                1,
                Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"),
                UserRef.of("agent"));
        task.getExecutionSpec().setDefaultMaxRetryCount(1);
        var epoch = RuntimeEpoch.of("task-1", 1L);
        var policy = ResolvedTaskSchedulingPolicy.from(task, null);
        runtime.putRuntimeMeta(new TaskRuntimeMetaV1(
                "task-1",
                "default",
                RuntimeGate.OPEN,
                epoch,
                DUE,
                0L,
                0L,
                0L,
                resultPolicy(policy)));
        runtime.appendBacklog(
                "task-1",
                List.of(new AppendItemInput("message-1", "", Map.of("value", 1), null)),
                10);

        var selectedWorker = SelectedWorkerHandle.of("worker-1", "group-1", "scope-1", true);
        var claimPolicy = TaskRuntimePolicySnapshotMapper.toClaimLeasePolicy(task, policy, 1, 30L);
        var claimed = runtime.claimBacklog(
                runtime.scoreCandidate("task-1", "default").orElseThrow(),
                List.of(TaskRuntimeWorkerReservationMapper.toReservationEvidence(selectedWorker, "batch-1")),
                claimPolicy.maxItems(),
                claimPolicy.leaseMillis(),
                1L)
                .claimedItems()
                .getFirst();
        var dispatchBinding = TaskRuntimeDispatchBindingMapper.fromTaskRuntimeClaim(task, claimed);

        var outcome = runtime.applyResult(TaskRuntimeResultFactMapper.fromDispatchSubmitFailure(
                dispatchBinding,
                epoch,
                1_500L,
                "dispatch submit failed"));
        var decision = TaskRuntimeResultDecisionMapper.toEngineDecision(outcome);
        var progress = runtime.progressSnapshot("task-1");

        assertThat(decision.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(decision.retryScheduled()).isTrue();
        assertThat(progress.readyCount()).isEqualTo(1L);
        assertThat(progress.activeCount()).isZero();
        assertThat(progress.finalCount()).isZero();
    }

    private static TaskRuntimeResultPolicyV1 resultPolicy(ResolvedTaskSchedulingPolicy policy) {
        return TaskRuntimeResultPolicyV1.from(
                TaskRuntimePolicySnapshotMapper.toRetryPolicySnapshot(policy, -1, 1L),
                TaskRuntimePolicySnapshotMapper.toResultFinalityPolicySnapshot(policy, 86_400_000L));
    }
}
