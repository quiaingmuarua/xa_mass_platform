package com.xa.mass.task.runtime.redis;

import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

final class TaskRuntimeRedisKeyspaceProofHarness {

    static final long TIME_SCORE_FLOOR = TaskScoreV1.TIME_SCORE_FLOOR;

    private final RedisCommands<String, String> commands;
    private final TaskRuntimeRedisKeyCodecV1 codec;
    private final TaskRuntimeRedisKeyspaceV1 keyspace;
    private final TaskRuntimeRedisFrameCodecV1 frameCodec;
    private final LongSupplier clock;

    TaskRuntimeRedisKeyspaceProofHarness(RedisCommands<String, String> commands,
                                         String namespace,
                                         LongSupplier clock) {
        this.commands = commands;
        this.codec = new TaskRuntimeRedisKeyCodecV1();
        this.keyspace = new TaskRuntimeRedisKeyspaceV1(namespace, codec);
        this.frameCodec = new TaskRuntimeRedisFrameCodecV1();
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    TaskRuntimeRedisKeyspaceV1 keyspace() {
        return keyspace;
    }

    TaskRuntimeRedisFrameCodecV1 frameCodec() {
        return frameCodec;
    }

    void putRuntimeMeta(TaskRuntimeMetaV1 meta) {
        commands.sadd(keyspace.lanesKey(), meta.laneKey());
        commands.hset(keyspace.taskMetaKey(meta.taskId()), Map.of(
                "schemaVersion", "1",
                "taskId", meta.taskId(),
                "laneBucketId", meta.laneKey(),
                "runtimeGate", meta.runtimeGate().name(),
                "runtimeEpoch", Long.toString(meta.runtimeEpoch().epoch()),
                "fenceToken", meta.runtimeEpoch().fenceToken() == null ? "" : meta.runtimeEpoch().fenceToken(),
                "updatedAtMillis", Long.toString(clock.getAsLong())
        ));
    }

    void markDispatchDue(String taskId, String laneKey, RuntimeEpoch epoch, long nowMillis) {
        commands.sadd(keyspace.lanesKey(), laneKey);
        commands.zadd(
                keyspace.taskScoreKey(laneKey),
                TaskScoreV1.dueAt(nowMillis).score(),
                codec.encodeSegment(taskId));
        commands.hset(keyspace.taskMetaKey(taskId), Map.of(
                "schemaVersion", "1",
                "taskId", taskId,
                "laneBucketId", laneKey,
                "runtimeGate", "OPEN",
                "runtimeEpoch", Long.toString(epoch.epoch()),
                "fenceToken", epoch.fenceToken() == null ? "" : epoch.fenceToken(),
                "updatedAtMillis", Long.toString(clock.getAsLong())
        ));
    }

    void appendBacklog(String taskId, List<AppendItemInput> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must be non-empty");
        }
        var encoded = frames.stream()
                .map(frame -> frameCodec.encodeBacklogFrame(taskId, frame, clock.getAsLong()))
                .toArray(String[]::new);
        commands.rpush(keyspace.taskBacklogKey(taskId), encoded);
    }

    ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit) {
        var encodedTaskIds = commands.zrangebyscore(
                keyspace.taskScoreKey(laneKey),
                Range.create((double) TaskScoreV1.TIME_SCORE_FLOOR, (double) maxScore),
                Limit.create(0, Math.max(1, limit)));
        var candidates = new ArrayList<ScoreCandidate>();
        for (var encodedTaskId : encodedTaskIds) {
            String taskId = codec.decodeSegment(encodedTaskId);
            Map<String, String> meta = commands.hgetall(keyspace.taskMetaKey(taskId));
            long epoch = parseLong(meta.get("runtimeEpoch"));
            Double score = commands.zscore(keyspace.taskScoreKey(laneKey), encodedTaskId);
            candidates.add(new ScoreCandidate(
                    taskId,
                    laneKey,
                    RuntimeEpoch.of(taskId, epoch),
                    new TaskScoreV1(score == null ? TaskScoreV1.TIME_SCORE_FLOOR : score.longValue())));
        }
        return new ScoreCandidateBatch(candidates);
    }

    List<String> keys(String namespace) {
        return commands.keys(namespace + ":*");
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }
}
