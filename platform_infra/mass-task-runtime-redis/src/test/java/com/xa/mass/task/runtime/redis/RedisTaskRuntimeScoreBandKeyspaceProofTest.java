package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.BacklogFrameV1;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeScoreBandKeyspaceProofTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private String redisUri;
    private String namespace;

    @AfterEach
    void cleanup() {
        if (connection != null) {
            connection.close();
            connection = null;
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
    void proofAppendAndDiscoveryUseOnlyApprovedScoreBandKeys() {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("score-band-proof");
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime score-band keyspace proof");
        connection = redisClient.connect();

        var commands = connection.sync();
        var harness = new TaskRuntimeRedisKeyspaceProofHarness(commands, namespace, () -> 1_700_000_000_000L);
        String taskId = "task/needs encoding";
        String laneKey = "project.demo";
        var epoch = RuntimeEpoch.of(taskId, 7L);

        harness.putRuntimeMeta(new TaskRuntimeMetaV1(
                taskId,
                laneKey,
                RuntimeGate.OPEN,
                epoch,
                TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR,
                0L,
                0L,
                0L));
        harness.setTaskScore(
                taskId,
                laneKey,
                epoch,
                new TaskScoreV1(TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR));
        harness.appendBacklog(taskId, List.of(new BacklogFrameV1(
                "message-1",
                "handler.demo",
                Map.of("value", "payload"),
                null)));

        var keyspace = harness.keyspace();
        assertThat(commands.type(keyspace.lanesKey())).isEqualTo("set");
        assertThat(commands.type(keyspace.taskScoreKey(laneKey))).isEqualTo("zset");
        assertThat(commands.type(keyspace.taskMetaKey(taskId))).isEqualTo("hash");
        assertThat(commands.type(keyspace.taskBacklogKey(taskId))).isEqualTo("list");

        assertThat(commands.smembers(keyspace.lanesKey())).containsExactly(laneKey);
        assertThat(commands.hget(keyspace.taskMetaKey(taskId), "taskId")).isEqualTo(taskId);
        assertThat(commands.hget(keyspace.taskMetaKey(taskId), "laneBucketId")).isEqualTo(laneKey);

        var frame = harness.frameCodec().decodeFrame(commands.lindex(keyspace.taskBacklogKey(taskId), 0));
        assertThat(frame)
                .containsEntry("schemaVersion", 1.0)
                .containsEntry("frameType", "RAW")
                .containsEntry("taskId", taskId)
                .containsEntry("messageId", "message-1")
                .containsEntry("retryCount", 0.0);
        assertThat(frame.get("payloadJson")).isInstanceOf(Map.class);

        var candidates = harness.discoverSchedulable(
                laneKey,
                TaskRuntimeRedisKeyspaceProofHarness.TIME_SCORE_FLOOR,
                10);
        assertThat(candidates.candidates()).hasSize(1);
        assertThat(candidates.candidates().getFirst().taskId()).isEqualTo(taskId);
        assertThat(candidates.candidates().getFirst().runtimeEpoch()).isEqualTo(epoch);

        assertThat(harness.keys(namespace))
                .containsExactlyInAnyOrder(
                        keyspace.lanesKey(),
                        keyspace.taskScoreKey(laneKey),
                        keyspace.taskMetaKey(taskId),
                        keyspace.taskBacklogKey(taskId))
                .noneMatch(key -> key.contains(":ids"))
                .noneMatch(key -> key.contains(":dirty"))
                .noneMatch(key -> key.endsWith(":tasks"))
                .noneMatch(key -> key.contains(":ready"))
                .noneMatch(key -> key.contains(":final:order"))
                .noneMatch(key -> key.contains(":worker:"));
    }
}
