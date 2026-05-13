package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRetentionPolicy;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class RedisTaskResultRuntime implements TaskResultRuntime {

    private static final String PENDING = "__PENDING__";
    private static final String CLAIMED = "CLAIMED";
    private static final String DONE = "DONE";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantMillisAdapter())
            .create();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisTaskResultKeyspace keyspace;
    private final Supplier<Instant> clock;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisTaskResultRuntime(String redisUri) {
        this(redisUri, RedisTaskResultKeyspace.DEFAULT_NAMESPACE);
    }

    public RedisTaskResultRuntime(String redisUri, String namespace) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespace,
                Instant::now,
                true);
    }

    RedisTaskResultRuntime(RedisClient redisClient,
                           String namespace,
                           Supplier<Instant> clock,
                           boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                new RedisTaskResultKeyspace(namespace),
                clock,
                ownsClient);
    }

    RedisTaskResultRuntime(StatefulRedisConnection<String, String> connection,
                           RedisTaskResultKeyspace keyspace,
                           Supplier<Instant> clock) {
        this(null, connection, keyspace, clock, false);
    }

    private RedisTaskResultRuntime(RedisClient redisClient,
                                   StatefulRedisConnection<String, String> connection,
                                   RedisTaskResultKeyspace keyspace,
                                   Supplier<Instant> clock,
                                   boolean ownsClient) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownsClient = ownsClient;
    }

    @Override
    public StageResult stageCallback(TaskResultCallbackDraft draft) {
        if (!running.get()) {
            return StageResult.unavailable("result runtime is stopped");
        }
        if (draft == null) {
            return StageResult.rejected("draft must not be null");
        }
        String key = keyspace.stagedDraft(draft.stageId());
        String json = GSON.toJson(draft);
        Boolean inserted = commands.setnx(key, json);
        if (Boolean.TRUE.equals(inserted)) {
            commands.sadd(keyspace.taskStagesSet(draft.taskId()), draft.stageId());
            commands.zadd(keyspace.allStagesZset(), toScore(draft.receivedAt()), draft.stageId());
            return StageResult.staged(draft);
        }
        TaskResultCallbackDraft existing = loadDraft(draft.stageId());
        return StageResult.duplicate(existing != null ? existing : draft);
    }

    @Override
    public boolean discardStagedCallback(String stageId) {
        if (isBlank(stageId)) {
            return false;
        }
        TaskResultCallbackDraft draft = loadDraft(stageId);
        long deleted = commands.del(keyspace.stagedDraft(stageId));
        commands.zrem(keyspace.allStagesZset(), stageId);
        if (draft != null) {
            commands.srem(keyspace.taskStagesSet(draft.taskId()), stageId);
        }
        return deleted > 0L;
    }

    @Override
    public synchronized CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft) {
        if (!running.get()) {
            return CommitResult.unavailable("result runtime is stopped");
        }
        if (finalDraft == null) {
            return CommitResult.rejected("finalDraft must not be null");
        }
        String rowKey = keyspace.taskVisibleRow(finalDraft.taskId(), finalDraft.messageId());
        String existing = commands.get(rowKey);
        if (!isBlank(existing) && !PENDING.equals(existing)) {
            return CommitResult.duplicate(GSON.fromJson(existing, TaskResultRuntimeRow.class));
        }
        if (PENDING.equals(existing)) {
            return CommitResult.unavailable("visible row commit is pending");
        }
        Boolean claimed = commands.setnx(rowKey, PENDING);
        if (!Boolean.TRUE.equals(claimed)) {
            String rowJson = commands.get(rowKey);
            if (!isBlank(rowJson) && !PENDING.equals(rowJson)) {
                return CommitResult.duplicate(GSON.fromJson(rowJson, TaskResultRuntimeRow.class));
            }
            return CommitResult.unavailable("visible row commit is pending");
        }
        long seq = commands.incr(keyspace.taskSeqCounter(finalDraft.taskId()));
        TaskResultRuntimeRow row = rowFromDraft(finalDraft, seq, false, false);
        commands.set(rowKey, GSON.toJson(row));
        commands.zadd(keyspace.taskVisibleZset(finalDraft.taskId()), seq, finalDraft.messageId());
        return CommitResult.committed(row);
    }

    @Override
    public List<TaskResultRepairCandidate> scanRepairCandidates(int limit) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        List<String> stageIds = commands.zrange(keyspace.allStagesZset(), 0, Math.max(0, limit * 4L - 1L));
        List<TaskResultRepairCandidate> candidates = new ArrayList<>(Math.min(limit, stageIds.size()));
        for (String stageId : stageIds) {
            TaskResultCallbackDraft draft = loadDraft(stageId);
            if (draft == null) {
                commands.zrem(keyspace.allStagesZset(), stageId);
                continue;
            }
            if (getVisibleByMessageId(draft.taskId(), draft.messageId()).isPresent()) {
                continue;
            }
            candidates.add(new TaskResultRepairCandidate(draft));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return List.copyOf(candidates);
    }

    @Override
    public CommitResult repairVisibleFinal(TaskResultRepairCandidate candidate) {
        if (candidate == null || candidate.draft() == null) {
            return CommitResult.rejected("candidate must not be null");
        }
        TaskResultCallbackDraft draft = candidate.draft();
        Instant now = clock.get();
        return commitVisibleFinal(new TaskResultFinalDraft(
                draft.taskId(),
                draft.messageId(),
                draft.eventCode(),
                draft.success() ? "SUCCESS" : "FAILED",
                draft.success() ? "BUSINESS_SUCCESS" : "BUSINESS_FAILED",
                draft.retryCount(),
                draft.maxRetryCount(),
                draft.workerId(),
                draft.workerContextId(),
                draft.batchId(),
                draft.attemptId(),
                draft.payloadRef(),
                draft.createTime() != null ? draft.createTime() : draft.receivedAt(),
                draft.leasedAt(),
                draft.leasedAt(),
                now,
                now,
                draft.errorCode(),
                draft.detail(),
                draft.output(),
                draft.stageId()
        ));
    }

    @Override
    public BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq) {
        return claimBarrier(keyspace.logicalFinalBarrier(taskId, messageId, finalSeq), taskId, messageId, finalSeq, true);
    }

    @Override
    public void markLogicalFinalPublished(String taskId, String messageId, long finalSeq) {
        markBarrier(keyspace.logicalFinalBarrier(taskId, messageId, finalSeq), taskId, messageId, finalSeq, true);
    }

    @Override
    public BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq) {
        return claimBarrier(keyspace.progressBarrier(taskId, messageId, finalSeq), taskId, messageId, finalSeq, false);
    }

    @Override
    public void markProgressApplied(String taskId, String messageId, long finalSeq) {
        markBarrier(keyspace.progressBarrier(taskId, messageId, finalSeq), taskId, messageId, finalSeq, false);
    }

    @Override
    public TaskResultWindow readWindow(String taskId, long afterSeq, int limit) {
        if (isBlank(taskId)) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        int boundedLimit = Math.max(0, limit);
        if (!running.get() || boundedLimit == 0) {
            return new TaskResultWindow(taskId, List.of(), Math.max(0L, afterSeq), false, countVisibleResults(taskId));
        }
        List<String> messageIds = commands.zrangebyscore(
                keyspace.taskVisibleZset(taskId),
                Range.create((double) Math.max(0L, afterSeq + 1L), Double.POSITIVE_INFINITY),
                Limit.create(0, boundedLimit)
        );
        List<TaskResultRuntimeRow> rows = new ArrayList<>(messageIds.size());
        for (String messageId : messageIds) {
            getVisibleByMessageId(taskId, messageId).ifPresent(rows::add);
        }
        long nextAfterSeq = rows.isEmpty() ? Math.max(0L, afterSeq) : rows.get(rows.size() - 1).seq();
        Long higher = commands.zcount(
                keyspace.taskVisibleZset(taskId),
                Range.create((double) nextAfterSeq + 1D, Double.POSITIVE_INFINITY)
        );
        long total = countVisibleResults(taskId);
        return new TaskResultWindow(taskId, rows, nextAfterSeq, higher != null && higher > 0L, total);
    }

    @Override
    public long countVisibleResults(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        Long count = commands.zcard(keyspace.taskVisibleZset(taskId));
        return count == null ? 0L : count;
    }

    @Override
    public Optional<TaskResultRuntimeRow> getVisibleByMessageId(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        String json = commands.get(keyspace.taskVisibleRow(taskId, messageId));
        if (isBlank(json) || PENDING.equals(json)) {
            return Optional.empty();
        }
        return Optional.of(GSON.fromJson(json, TaskResultRuntimeRow.class));
    }

    @Override
    public long compactTerminalTask(String taskId, TaskResultRetentionPolicy policy) {
        if (isBlank(taskId) || policy == null || policy.keepLatestRows() == Long.MAX_VALUE) {
            return 0L;
        }
        long count = countVisibleResults(taskId);
        long removeCount = count - policy.keepLatestRows();
        if (removeCount <= 0L) {
            return 0L;
        }
        List<String> messageIds = commands.zrange(keyspace.taskVisibleZset(taskId), 0, removeCount - 1);
        for (String messageId : messageIds) {
            commands.del(keyspace.taskVisibleRow(taskId, messageId));
            commands.zrem(keyspace.taskVisibleZset(taskId), messageId);
        }
        return messageIds.size();
    }

    @Override
    public long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        long removed = 0L;
        List<String> stageIds = new ArrayList<>(commands.smembers(keyspace.taskStagesSet(taskId)));
        for (String stageId : stageIds) {
            if (commands.del(keyspace.stagedDraft(stageId)) > 0L) {
                removed++;
            }
            commands.zrem(keyspace.allStagesZset(), stageId);
        }
        commands.del(keyspace.taskStagesSet(taskId));
        List<String> messageIds = commands.zrange(keyspace.taskVisibleZset(taskId), 0, -1);
        for (String messageId : messageIds) {
            if (commands.del(keyspace.taskVisibleRow(taskId, messageId)) > 0L) {
                removed++;
            }
        }
        commands.del(keyspace.taskVisibleZset(taskId), keyspace.taskSeqCounter(taskId));
        return removed;
    }

    @Override
    public void shutdown() {
        running.set(false);
        closeRedisResources();
    }

    private BarrierClaim claimBarrier(String barrierKey,
                                      String taskId,
                                      String messageId,
                                      long finalSeq,
                                      boolean logicalFinal) {
        Optional<TaskResultRuntimeRow> row = getVisibleByMessageId(taskId, messageId);
        if (row.isEmpty() || row.get().seq() != finalSeq) {
            return BarrierClaim.rejected();
        }
        if ((logicalFinal && row.get().logicalFinalPublished()) || (!logicalFinal && row.get().progressApplied())) {
            return BarrierClaim.alreadyDone();
        }
        String existing = commands.get(barrierKey);
        if (DONE.equals(existing)) {
            return BarrierClaim.alreadyDone();
        }
        if (CLAIMED.equals(existing)) {
            return BarrierClaim.busy();
        }
        Boolean claimed = commands.setnx(barrierKey, CLAIMED);
        return Boolean.TRUE.equals(claimed) ? BarrierClaim.claimed() : BarrierClaim.busy();
    }

    private void markBarrier(String barrierKey,
                             String taskId,
                             String messageId,
                             long finalSeq,
                             boolean logicalFinal) {
        commands.set(barrierKey, DONE);
        Optional<TaskResultRuntimeRow> row = getVisibleByMessageId(taskId, messageId);
        if (row.isEmpty() || row.get().seq() != finalSeq) {
            return;
        }
        TaskResultRuntimeRow updated = logicalFinal
                ? row.get().withLogicalFinalPublished()
                : row.get().withProgressApplied();
        commands.set(keyspace.taskVisibleRow(taskId, messageId), GSON.toJson(updated));
    }

    private TaskResultCallbackDraft loadDraft(String stageId) {
        String json = commands.get(keyspace.stagedDraft(stageId));
        if (isBlank(json)) {
            return null;
        }
        return GSON.fromJson(json, TaskResultCallbackDraft.class);
    }

    private TaskResultRuntimeRow rowFromDraft(TaskResultFinalDraft draft,
                                              long seq,
                                              boolean logicalFinalPublished,
                                              boolean progressApplied) {
        return new TaskResultRuntimeRow(
                draft.taskId(),
                draft.messageId(),
                seq,
                draft.eventCode(),
                draft.status(),
                draft.finalReason(),
                Math.max(0, draft.retryCount()),
                Math.max(0, draft.maxRetryCount()),
                draft.workerId(),
                draft.workerContextId(),
                draft.batchId(),
                draft.attemptId(),
                draft.payloadRef(),
                draft.createTime(),
                draft.assignedTime(),
                draft.startTime(),
                draft.completeTime(),
                draft.updateTime(),
                draft.errorCode(),
                draft.errorMessage(),
                draft.output(),
                logicalFinalPublished,
                progressApplied
        );
    }

    private void closeRedisResources() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            connection.close();
        } finally {
            if (ownsClient && redisClient != null) {
                redisClient.shutdown();
            }
        }
    }

    private static double toScore(Instant instant) {
        return instant == null ? 0D : instant.toEpochMilli();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class InstantMillisAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toEpochMilli());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            return switch (in.peek()) {
                case NULL -> {
                    in.nextNull();
                    yield null;
                }
                case NUMBER -> Instant.ofEpochMilli(in.nextLong());
                case STRING -> {
                    String value = in.nextString();
                    yield isBlank(value) ? null : Instant.ofEpochMilli(Long.parseLong(value));
                }
                default -> {
                    in.skipValue();
                    yield null;
                }
            };
        }
    }
}
