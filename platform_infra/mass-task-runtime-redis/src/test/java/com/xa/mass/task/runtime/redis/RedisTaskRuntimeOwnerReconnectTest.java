package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeOwnerReconnectTest {

    private static final String LANE = "default";
    private static final long DUE = TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR;

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
        String taskId = "task-owner-reconnect";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(new AppendItemInput(
                "message-1",
                "demo.dispatch",
                Map.of("value", 1),
                null)), 10);
        var firstItem = claimOne(taskId, "worker-before-reconnect", 1_000L);
        assertThat(firstItem.attemptNo()).isEqualTo(1);

        runtime.close();
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        clock.set(1_001L);

        var expired = runtime.scanExpiredLeases(LANE, clock.get(), 10, 10);
        assertThat(expired)
                .extracting(candidate -> candidate.messageId())
                .containsExactly("message-1");
        var expiredLease = expired.getFirst();
        var retry = runtime.applyResult(new RuntimeResultFact(
                expiredLease.taskId(),
                expiredLease.messageId(),
                expiredLease.leaseToken(),
                expiredLease.workerId(),
                expiredLease.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of(),
                "lease expired",
                epoch,
                clock.get()));
        assertThat(retry.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);

        var secondItem = claimOne(taskId, "worker-after-reconnect", 1_000L);
        assertThat(secondItem.attemptNo()).isEqualTo(2);

        var finality = runtime.applyResult(new RuntimeResultFact(
                secondItem.taskId(),
                secondItem.messageId(),
                secondItem.leaseToken(),
                secondItem.workerId(),
                secondItem.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                clock.get() + 1L));
        assertThat(finality.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);

        var finalRow = runtime.getFinalResultByMessageId(taskId, "message-1");
        assertThat(finalRow).isPresent();
        assertThat(finalRow.get().workerId()).isEqualTo("worker-after-reconnect");
        assertThat(finalRow.get().attemptNo()).isEqualTo(2);
    }

    private RuntimeEpoch enrollOpenTask(String taskId) {
        var epoch = RuntimeEpoch.of(taskId, 1L);
        runtime.putRuntimeMeta(new TaskRuntimeMetaV1(
                taskId,
                LANE,
                RuntimeGate.OPEN,
                epoch,
                DUE,
                0L,
                0L,
                0L,
                new TaskRuntimeResultPolicyV1(
                        RetryMode.FAST_READY,
                        1,
                        0L,
                        1L,
                        false,
                        true,
                        86_400_000L)));
        runtime.setTaskScore(taskId, LANE, epoch, new TaskScoreV1(DUE));
        return epoch;
    }

    private com.xa.mass.task.runtime.ClaimedWorkItem claimOne(String taskId, String workerId, long leaseMillis) {
        var candidate = runtime.discoverSchedulable(LANE, DUE, 10).candidates().getFirst();
        var claim = runtime.claimBacklog(
                candidate,
                List.of(new WorkerReservationEvidence(workerId, "group-1", "reservation-" + workerId, "target")),
                1,
                leaseMillis,
                0L);
        assertThat(claim.claimedItems()).hasSize(1);
        return claim.claimedItems().getFirst();
    }
}
