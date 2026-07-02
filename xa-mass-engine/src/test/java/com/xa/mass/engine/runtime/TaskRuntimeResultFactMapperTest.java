package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeResultFactMapperTest {

    @Test
    void mapsDispatchSubmitFailureToRuntimeResultFact() {
        var epoch = RuntimeEpoch.of("task-1", 3L);

        var fact = TaskRuntimeResultFactMapper.fromDispatchSubmitFailure(
                binding(),
                epoch,
                1_000L,
                " transport down ");

        assertThat(fact.taskId()).isEqualTo("task-1");
        assertThat(fact.messageId()).isEqualTo("message-1");
        assertThat(fact.leaseToken()).isEqualTo("lease-1");
        assertThat(fact.workerId()).isEqualTo("worker-1");
        assertThat(fact.attemptNo()).isEqualTo(2);
        assertThat(fact.source()).isEqualTo(ResultApplySource.DISPATCH_SUBMIT_FAILURE);
        assertThat(fact.success()).isFalse();
        assertThat(fact.failureReason()).isEqualTo("transport down");
        assertThat(fact.runtimeEpoch()).isEqualTo(epoch);
        assertThat(fact.observedAtMillis()).isEqualTo(1_000L);
    }

    @Test
    void mapsDispatchDeliveryFailureToRuntimeResultFact() {
        var fact = TaskRuntimeResultFactMapper.fromDispatchDeliveryFailure(
                binding(),
                RuntimeEpoch.of("task-1", 3L),
                1_000L,
                "no endpoint");

        assertThat(fact.source()).isEqualTo(ResultApplySource.DISPATCH_DELIVERY_FAILURE);
        assertThat(fact.failureReason()).isEqualTo("no endpoint");
    }

    @Test
    void mapsLeaseTimeoutCandidateToRuntimeResultFact() {
        var fact = TaskRuntimeResultFactMapper.fromLeaseTimeout(
                new ActiveLeaseRepairCandidate(
                        "task-1",
                        "message-1",
                        "lease-1",
                        "worker-1",
                        2,
                        1_000L),
                RuntimeEpoch.of("task-1", 3L),
                1_500L,
                "lease expired");

        assertThat(fact.source()).isEqualTo(ResultApplySource.LEASE_TIMEOUT);
        assertThat(fact.taskId()).isEqualTo("task-1");
        assertThat(fact.messageId()).isEqualTo("message-1");
        assertThat(fact.leaseToken()).isEqualTo("lease-1");
        assertThat(fact.workerId()).isEqualTo("worker-1");
        assertThat(fact.attemptNo()).isEqualTo(2);
        assertThat(fact.failureReason()).isEqualTo("lease expired");
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
