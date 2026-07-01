package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeResultCommandMapperTest {

    @Test
    void mapsDispatchSubmitFailureToResultApplyCommand() {
        var epoch = RuntimeEpoch.of("task-1", 3L);

        var command = TaskRuntimeResultCommandMapper.fromDispatchSubmitFailure(
                binding(),
                ResolvedTaskSchedulingPolicy.from(null, null),
                epoch,
                1_000L,
                " transport down ",
                7L,
                86_400_000L);

        assertThat(command.taskId()).isEqualTo("task-1");
        assertThat(command.messageId()).isEqualTo("message-1");
        assertThat(command.leaseToken()).isEqualTo("lease-1");
        assertThat(command.workerId()).isEqualTo("worker-1");
        assertThat(command.attemptNo()).isEqualTo(2);
        assertThat(command.source()).isEqualTo(ResultApplySource.DISPATCH_SUBMIT_FAILURE);
        assertThat(command.success()).isFalse();
        assertThat(command.failureReason()).isEqualTo("transport down");
        assertThat(command.retryPolicy().retryPolicyVersion()).isEqualTo(7L);
        assertThat(command.finalityPolicy().finalResultRetentionMillis()).isEqualTo(86_400_000L);
        assertThat(command.runtimeEpoch()).isEqualTo(epoch);
        assertThat(command.observedAtMillis()).isEqualTo(1_000L);
    }

    @Test
    void mapsDispatchDeliveryFailureToResultApplyCommand() {
        var command = TaskRuntimeResultCommandMapper.fromDispatchDeliveryFailure(
                binding(),
                ResolvedTaskSchedulingPolicy.from(null, null),
                RuntimeEpoch.of("task-1", 3L),
                1_000L,
                "no endpoint",
                7L,
                86_400_000L);

        assertThat(command.source()).isEqualTo(ResultApplySource.DISPATCH_DELIVERY_FAILURE);
        assertThat(command.failureReason()).isEqualTo("no endpoint");
    }

    @Test
    void mapsLeaseTimeoutCandidateToResultApplyCommand() {
        var command = TaskRuntimeResultCommandMapper.fromLeaseTimeout(
                new ActiveLeaseRepairCandidate(
                        "task-1",
                        "message-1",
                        "lease-1",
                        "worker-1",
                        2,
                        1_000L),
                ResolvedTaskSchedulingPolicy.from(null, null),
                RuntimeEpoch.of("task-1", 3L),
                1_500L,
                "lease expired",
                8L,
                86_400_000L);

        assertThat(command.source()).isEqualTo(ResultApplySource.LEASE_TIMEOUT);
        assertThat(command.taskId()).isEqualTo("task-1");
        assertThat(command.messageId()).isEqualTo("message-1");
        assertThat(command.leaseToken()).isEqualTo("lease-1");
        assertThat(command.workerId()).isEqualTo("worker-1");
        assertThat(command.attemptNo()).isEqualTo(2);
        assertThat(command.failureReason()).isEqualTo("lease expired");
        assertThat(command.retryPolicy().retryPolicyVersion()).isEqualTo(8L);
    }

    private static TaskDispatchBinding binding() {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                "message-1",
                "demo.event",
                Map.of("value", 1),
                "payload-ref-1",
                1,
                "attempt-1",
                2,
                "lease-1",
                "worker-1",
                "batch-1",
                "group-1",
                "selection-token-1",
                null,
                "demo:demo.event",
                "GROUP_SELECTOR");
    }
}
