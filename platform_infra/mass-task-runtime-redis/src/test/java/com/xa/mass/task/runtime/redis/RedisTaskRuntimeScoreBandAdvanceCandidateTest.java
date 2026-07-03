package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeScoreBandAdvanceCandidateTest {

    private static final String LANE = "default";
    private static final long DUE = TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> inspectionConnection;
    private RedisTaskRuntime runtime;
    private String redisUri;
    private String namespace;

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
    void redisTaskRuntimeAdvanceCandidateUsesScoreBandKeysForAppendClaimAndResult() {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("score-band-advance-candidate");
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime score-band advance candidate");
        inspectionConnection = redisClient.connect();
        runtime = new RedisTaskRuntime(redisClient, namespace, () -> 1_700_000_000_000L);

        String taskId = "task-serving";
        var epoch = RuntimeEpoch.of(taskId, 1L);
        runtime.putRuntimeMeta(new TaskRuntimeMetaV1(taskId, LANE, RuntimeGate.OPEN, epoch, DUE, 0L, 0L, 0L));
        runtime.markDispatchDue(taskId, LANE, epoch, DUE);
        runtime.appendBacklog(taskId, List.of(new AppendItemInput(
                "message-1",
                "handler.demo",
                Map.of("value", "payload"),
                null)), 10);

        var candidate = runtime.discoverSchedulable(LANE, DUE, 10).candidates().getFirst();
        var claim = runtime.claimBacklog(
                candidate,
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", "target-1")),
                1,
                5_000L,
                1_700_000_000_000L);
        var item = claim.claimedItems().getFirst();

        assertThat(runtime.resultCorrelation(taskId, "message-1").present()).isTrue();
        var finality = runtime.applyResult(new RuntimeResultFact(
                taskId,
                item.messageId(),
                item.leaseToken(),
                item.workerId(),
                item.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                1_700_000_000_000L));

        assertThat(finality.status()).isEqualTo(MessageFinalityStatus.LOGICAL_FINAL);
        assertThat(runtime.getFinalResultByMessageId(taskId, "message-1")).isPresent();
        assertThat(runtime.progressSnapshot(taskId).successCount()).isEqualTo(1);
        assertNoForbiddenOldKeys();
    }

    private void assertNoForbiddenOldKeys() {
        RedisCommands<String, String> commands = inspectionConnection.sync();
        assertThat(commands.keys(namespace + ":*"))
                .noneMatch(key -> key.contains(":ids"))
                .noneMatch(key -> key.contains(":dirty"))
                .noneMatch(key -> key.endsWith(":tasks"))
                .noneMatch(key -> key.contains(":ready"))
                .noneMatch(key -> key.contains(":delayed"))
                .noneMatch(key -> key.contains(":active"))
                .noneMatch(key -> key.contains(":final:order"))
                .noneMatch(key -> key.contains(":worker:"));
    }
}
