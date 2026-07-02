package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisScoreBandTaskRuntimeTest {

    private static final String LANE = "project.demo";
    private static final long DUE = TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> inspectionConnection;
    private RedisScoreBandTaskRuntime runtime;
    private String redisUri;
    private String namespace;
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);

    @AfterEach
    void cleanup() {
        if (inspectionConnection != null) {
            inspectionConnection.close();
            inspectionConnection = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        } else if (redisClient != null) {
            redisClient.shutdown();
        }
        if (namespace != null) {
            RedisTaskRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            namespace = null;
        }
        redisClient = null;
    }

    @Test
    void scoreBandRuntimeClaimsAndFinalizesThroughApprovedKeys() {
        start("score-band-runtime-success");
        String taskId = "task-success";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        var candidate = runtime.discoverSchedulable(LANE, DUE, 10).candidates().getFirst();
        var claim = runtime.claimBacklog(candidate, List.of(reservation("worker-1")), 1, 5_000L, clock.get());

        assertThat(claim.claimedItems()).hasSize(1);
        assertThat(commands().type(runtime.keyspace().taskRuntimeStateKey(taskId))).isEqualTo("hash");

        var claimed = claim.claimedItems().getFirst();
        var finality = runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                clock.get()));

        assertThat(finality.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(runtime.activeWorkForTask(taskId, 10).activeItems()).isEmpty();
        assertThat(runtime.getFinalResultByMessageId(taskId, "message-1")).isPresent();
        assertThat(commands().type(runtime.keyspace().taskResultKey(taskId))).isEqualTo("hash");
        assertNoForbiddenOldKeys();
    }

    @Test
    void scoreBandRuntimePromotesRetryAndFinalizesSecondFailureThroughApprovedKeys() {
        start("score-band-runtime-retry");
        String taskId = "task-retry";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        var first = claimOne(taskId, "worker-1");
        var retry = runtime.applyResult(new RuntimeResultFact(
                taskId,
                first.messageId(),
                first.leaseToken(),
                first.workerId(),
                first.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                false,
                Map.of(),
                "retryable failure",
                epoch,
                clock.get()));

        assertThat(retry.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId))
                .isEqualTo((double) TaskScoreV1.MAINT_ACTIVE);
        assertThat(commands().type(runtime.keyspace().taskRetryScoreKey(taskId))).isEqualTo("zset");
        assertThat(commands().type(runtime.keyspace().taskRetryItemKey(taskId))).isEqualTo("hash");

        clock.addAndGet(2_000L);
        var promoted = runtime.promoteDueRetries(LANE, clock.get(), 10, 10);
        assertThat(promoted).containsExactly("message-1");
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId))
                .isGreaterThanOrEqualTo((double) TaskScoreV1.TIME_SCORE_FLOOR);
        assertThat(commands().zcard(runtime.keyspace().taskRetryScoreKey(taskId))).isZero();
        assertThat(commands().hlen(runtime.keyspace().taskRetryItemKey(taskId))).isZero();
        assertThat(commands().llen(runtime.keyspace().taskBacklogKey(taskId))).isEqualTo(1L);
        var second = claimOne(taskId, "worker-2");
        assertThat(second.attemptNo()).isEqualTo(2);

        var finalFailure = runtime.applyResult(new RuntimeResultFact(
                taskId,
                second.messageId(),
                second.leaseToken(),
                second.workerId(),
                second.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                false,
                Map.of(),
                "final failure",
                epoch,
                clock.get()));

        assertThat(finalFailure.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(runtime.getFinalResultByMessageId(taskId, "message-1")).isPresent();
        assertThat(runtime.progressSnapshot(taskId).failedCount()).isEqualTo(1);
        assertNoForbiddenOldKeys();
    }

    @Test
    void scoreBandRuntimeRepairsExpiredLeaseWithoutDispatchOwningRepair() {
        start("score-band-runtime-lease-repair");
        String taskId = "task-lease";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        claimOne(taskId, "worker-1", 100L);
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId))
                .isEqualTo((double) TaskScoreV1.MAINT_ACTIVE);
        assertThat(runtime.discoverSchedulable(LANE, DUE, 10).candidates()).isEmpty();
        clock.addAndGet(500L);

        var repaired = runtime.scanExpiredLeases(LANE, clock.get(), 10, 10);

        assertThat(repaired).hasSize(1);
        assertThat(runtime.activeWorkForTask(taskId, 10).activeItems()).hasSize(1);

        var expired = repaired.getFirst();
        var retry = runtime.applyResult(new RuntimeResultFact(
                taskId,
                expired.messageId(),
                expired.leaseToken(),
                expired.workerId(),
                expired.attemptNo(),
                ResultApplySource.LEASE_TIMEOUT,
                false,
                Map.of(),
                "lease expired",
                epoch,
                clock.get()));

        assertThat(retry.status()).isEqualTo(MessageFinalityStatus.RETRY_SCHEDULED);
        assertThat(runtime.activeWorkForTask(taskId, 10).activeItems()).isEmpty();
        assertThat(commands().zcard(runtime.keyspace().taskRetryScoreKey(taskId))).isEqualTo(1L);
        assertNoForbiddenOldKeys();
    }

    @Test
    void scoreBandRuntimeRejectsStaleScoreCandidateWithoutMovingBacklog() {
        start("score-band-runtime-stale-claim");
        String taskId = "task-stale-claim";
        var epoch = enrollOpenTask(taskId, RuntimeEpoch.of(taskId, 1L, "fence-1"));

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        var staleCandidate = runtime.discoverSchedulable(LANE, DUE, 10).candidates().getFirst();

        var nextEpoch = RuntimeEpoch.of(taskId, 2L, "fence-2");
        enrollOpenTask(taskId, nextEpoch);

        var staleClaim = runtime.claimBacklog(
                staleCandidate,
                List.of(reservation("worker-1")),
                1,
                5_000L,
                clock.get());

        assertThat(staleClaim.claimedItems()).isEmpty();
        assertThat(staleClaim.rejectionReason()).contains("mismatch");
        assertThat(commands().llen(runtime.keyspace().taskBacklogKey(taskId))).isEqualTo(1L);
        assertThat(commands().hlen(runtime.keyspace().taskRuntimeStateKey(taskId))).isZero();

        var freshCandidate = runtime.discoverSchedulable(LANE, DUE, 10).candidates().getFirst();
        var freshClaim = runtime.claimBacklog(
                freshCandidate,
                List.of(reservation("worker-1")),
                1,
                5_000L,
                clock.get());
        assertThat(freshClaim.claimedItems()).hasSize(1);
        assertThat(freshClaim.claimedItems().getFirst().messageId()).isEqualTo("message-1");
        assertThat(epoch.fenceToken()).isEqualTo("fence-1");
        assertNoForbiddenOldKeys();
    }

    @Test
    void scoreBandRuntimeRejectsMaintenanceBandCandidateWithoutMovingBacklog() {
        start("score-band-runtime-maintenance-candidate");
        String taskId = "task-maintenance-candidate";
        var epoch = enrollOpenTask(taskId, RuntimeEpoch.of(taskId, 1L, "fence-1"));

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        var rejected = runtime.claimBacklog(
                new ScoreCandidate(taskId, LANE, epoch, TaskScoreV1.maintActive()),
                List.of(reservation("worker-1")),
                1,
                5_000L,
                clock.get());

        assertThat(rejected.claimedItems()).isEmpty();
        assertThat(rejected.rejectionReason()).contains("dispatch-visible");
        assertThat(commands().llen(runtime.keyspace().taskBacklogKey(taskId))).isEqualTo(1L);
        assertThat(commands().hlen(runtime.keyspace().taskRuntimeStateKey(taskId))).isZero();
        assertNoForbiddenOldKeys();
    }

    @Test
    void scoreBandRuntimeRejectsDuplicateAndStaleResultsWithoutMutatingFinalTruth() {
        start("score-band-runtime-stale");
        String taskId = "task-stale";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-1")), 10);
        var claimed = claimOne(taskId, "worker-1");

        var stale = runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                "wrong-token",
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of(),
                "",
                epoch,
                clock.get()));
        assertThat(stale.status()).isEqualTo(MessageFinalityStatus.DUPLICATE_OR_LATE);
        assertThat(runtime.activeWorkForTask(taskId, 10).activeItems()).hasSize(1);

        runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of(),
                "",
                epoch,
                clock.get()));
        var duplicate = runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of(),
                "",
                epoch,
                clock.get()));

        assertThat(duplicate.status()).isEqualTo(MessageFinalityStatus.DUPLICATE_OR_LATE);
        assertThat(runtime.getFinalResultByMessageId(taskId, claimed.messageId())).isPresent();
        assertNoForbiddenOldKeys();
    }

    @Test
    void closeIfDrainedDoesNotRemoveTaskScoreWhenRetryItemResidueExists() {
        start("score-band-runtime-close");
        String taskId = "task-close";
        var epoch = enrollOpenTask(taskId);

        commands().hset(runtime.keyspace().taskRetryItemKey(taskId), "message-1", "{}");

        var deferred = runtime.closeIfDrained(taskId, LANE, epoch);
        assertThat(deferred).isFalse();
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId)).isNotNull();

        commands().hdel(runtime.keyspace().taskRetryItemKey(taskId), "message-1");
        var closed = runtime.closeIfDrained(taskId, LANE, epoch);
        assertThat(closed).isTrue();
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId)).isNull();
        assertNoForbiddenOldKeys();
    }

    @Test
    void discardRuntimeDeletesTaskLocalTruthAndScoreMembership() {
        start("score-band-runtime-discard");
        String taskId = "task-discard-runtime";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-ready"), frame("message-final")), 10);
        var claimed = claimOne(taskId, "worker-1");
        runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                clock.get()));

        runtime.discardRuntime(taskId, LANE, epoch, "delete task");

        assertThat(commands().exists(
                runtime.keyspace().taskBacklogKey(taskId),
                runtime.keyspace().taskRetryScoreKey(taskId),
                runtime.keyspace().taskRetryItemKey(taskId),
                runtime.keyspace().taskRuntimeStateKey(taskId),
                runtime.keyspace().taskResultKey(taskId),
                runtime.keyspace().taskMetaKey(taskId))).isZero();
        assertThat(commands().zscore(runtime.keyspace().taskScoreKey(LANE), taskId)).isNull();
        assertNoForbiddenOldKeys();
    }

    @Test
    void discardWorkDeletesMutableWorkTruthButKeepsFinalResultRows() {
        start("score-band-runtime-discard-work");
        String taskId = "task-discard-work";
        var epoch = enrollOpenTask(taskId);

        runtime.appendBacklog(taskId, List.of(frame("message-final"), frame("message-ready")), 10);
        var claimed = claimOne(taskId, "worker-1");
        runtime.applyResult(new RuntimeResultFact(
                taskId,
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                clock.get()));

        runtime.discardWork(taskId, epoch, "discard work");

        assertThat(commands().exists(
                runtime.keyspace().taskBacklogKey(taskId),
                runtime.keyspace().taskRetryScoreKey(taskId),
                runtime.keyspace().taskRetryItemKey(taskId),
                runtime.keyspace().taskRuntimeStateKey(taskId))).isZero();
        assertThat(runtime.getFinalResultByMessageId(taskId, claimed.messageId())).isPresent();
        assertNoForbiddenOldKeys();
    }

    private void start(String label) {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace(label);
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip(label);
        inspectionConnection = redisClient.connect();
        runtime = new RedisScoreBandTaskRuntime(redisClient, namespace, clock::get);
    }

    private RuntimeEpoch enrollOpenTask(String taskId) {
        return enrollOpenTask(taskId, RuntimeEpoch.of(taskId, 1L));
    }

    private RuntimeEpoch enrollOpenTask(String taskId, RuntimeEpoch epoch) {
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
                        RetryMode.DUE_TIME,
                        1,
                        1_000L,
                        1L,
                        false,
                        true,
                        86_400_000L)));
        runtime.setTaskScore(taskId, LANE, epoch, new TaskScoreV1(DUE));
        return epoch;
    }

    private com.xa.mass.task.runtime.ClaimedWorkItem claimOne(String taskId, String workerId) {
        return claimOne(taskId, workerId, 5_000L);
    }

    private com.xa.mass.task.runtime.ClaimedWorkItem claimOne(String taskId, String workerId, long leaseMillis) {
        var candidate = runtime.discoverSchedulable(LANE, Math.max(DUE, clock.get()), 10).candidates().getFirst();
        var claim = runtime.claimBacklog(candidate, List.of(reservation(workerId)), 1, leaseMillis, clock.get());
        assertThat(claim.claimedItems()).hasSize(1);
        return claim.claimedItems().getFirst();
    }

    private AppendItemInput frame(String messageId) {
        return new AppendItemInput(messageId, "handler.demo", Map.of("value", messageId), null);
    }

    private WorkerReservationEvidence reservation(String workerId) {
        return new WorkerReservationEvidence(workerId, "group-1", "reservation-" + workerId, "dispatch-target");
    }

    private RedisCommands<String, String> commands() {
        return inspectionConnection.sync();
    }

    private void assertNoForbiddenOldKeys() {
        assertThat(commands().keys(namespace + ":*"))
                .noneMatch(key -> key.contains(":ids"))
                .noneMatch(key -> key.contains(":dirty"))
                .noneMatch(key -> key.endsWith(":tasks"))
                .noneMatch(key -> key.contains(":ready"))
                .noneMatch(key -> key.contains(":final:order"))
                .noneMatch(key -> key.contains(":worker:"));
    }
}
