package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.PollActiveLeaseRepairCommand;
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
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeOwnerReconnectTest {

    private RedisClient redisClient;
    private String redisUri;
    private String namespace;
    private RedisTaskRuntime runtime;

    @AfterEach
    void cleanup() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (namespace != null) {
            RedisTaskRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            namespace = null;
        }
        if (redisClient != null) {
            redisClient.shutdown();
            redisClient = null;
        }
    }

    @Test
    void activeLeaseCanBeRepairedAfterRuntimeOwnerReconnect() {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("owner-reconnect");
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime redis owner reconnect test");
        var clock = new AtomicLong(0L);
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        var epoch = RuntimeEpoch.of("task-owner-reconnect", 1L);

        runtime.updateTaskEligibility(new UpdateSchedulerEligibilityCommand(
                "task-owner-reconnect",
                new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L),
                epoch));
        var appended = runtime.appendBatch(new AppendBatchCommand(
                "task-owner-reconnect",
                List.of(new AppendItemInput("message-1", "demo.dispatch", Map.of("value", 1), null)),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                epoch));
        assertThat(appended.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);

        var firstClaim = runtime.claimReady(new ClaimReadyCommand(
                "task-owner-reconnect",
                List.of(new WorkerReservationEvidence("worker-before-reconnect", "group-1", "reservation-1", "target-1")),
                new ClaimLeasePolicy(1, 1_000L, 1L, epoch)));
        assertThat(firstClaim.claimedItems()).hasSize(1);
        var firstItem = firstClaim.claimedItems().getFirst();
        assertThat(firstItem.attemptNo()).isEqualTo(1);

        runtime.close();
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        clock.set(1_001L);

        var expired = runtime.pollExpiredActiveLeases(new PollActiveLeaseRepairCommand(10, clock.get()));
        assertThat(expired.candidates())
                .extracting(candidate -> candidate.messageId())
                .containsExactly("message-1");
        var repaired = expired.candidates().getFirst();
        var timeout = runtime.applyResult(new ResultApplyCommand(
                repaired.taskId(),
                repaired.messageId(),
                repaired.leaseToken(),
                repaired.workerId(),
                repaired.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of("reason", "owner reconnect timeout"),
                "expired after runtime owner reconnect",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 1, 0L, 1L),
                new ResultFinalityPolicySnapshot(true, true, 86_400_000L),
                epoch,
                clock.get()));
        assertThat(timeout.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);

        assertThat(runtime.discoverEligibleTasks(new SchedulerDiscoveryCommand(10, clock.get())).candidates())
                .extracting(candidate -> candidate.taskId())
                .containsExactly("task-owner-reconnect");
        var secondClaim = runtime.claimReady(new ClaimReadyCommand(
                "task-owner-reconnect",
                List.of(new WorkerReservationEvidence("worker-after-reconnect", "group-1", "reservation-2", "target-2")),
                new ClaimLeasePolicy(1, 1_000L, 1L, epoch)));
        assertThat(secondClaim.claimedItems()).hasSize(1);
        var secondItem = secondClaim.claimedItems().getFirst();
        assertThat(secondItem.attemptNo()).isEqualTo(2);

        var finality = runtime.applyResult(new ResultApplyCommand(
                secondItem.taskId(),
                secondItem.messageId(),
                secondItem.leaseToken(),
                secondItem.workerId(),
                secondItem.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 1, 0L, 1L),
                new ResultFinalityPolicySnapshot(true, true, 86_400_000L),
                epoch,
                clock.get() + 1L));
        assertThat(finality.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);

        var finalRows = runtime.readFinalResults(new FinalResultReadRequest("task-owner-reconnect", 0, 10)).rows();
        assertThat(finalRows).hasSize(1);
        assertThat(finalRows.getFirst().workerId()).isEqualTo("worker-after-reconnect");
        assertThat(finalRows.getFirst().attemptNo()).isEqualTo(2);
    }
}
