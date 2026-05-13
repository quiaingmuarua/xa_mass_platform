package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierMarkResult;
import com.xa.mass.runtime.api.CommitResult;
import com.xa.mass.runtime.api.StageResult;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRepairCandidate;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.TaskResultWindow;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Redis result runtime targets standalone or single-shard Redis only.
 *
 * <p>The current Lua commit and barrier scripts use multiple task-local keys
 * plus global pending-repair indexes and do not attempt Redis Cluster
 * cross-slot support.</p>
 */
public final class RedisTaskResultRuntime implements TaskResultRuntime {

    private static final String STATUS_COMMITTED = "COMMITTED";
    private static final String STATUS_DUPLICATE = "DUPLICATE";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_ALREADY_DONE = "ALREADY_DONE";
    private static final String STATUS_BUSY = "BUSY";
    private static final String STATUS_MARKED = "MARKED";
    private static final String STATUS_TOKEN_MISMATCH = "TOKEN_MISMATCH";
    private static final String PENDING_SEPARATOR = "\t";
    private static final long DEFAULT_BARRIER_TTL_MILLIS = Long.getLong(
            "xa.mass.runtime.resultBarrierClaimTtlMillis", 30_000L);
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter(Instant.class, new InstantMillisAdapter())
            .create();
    private static final String COMMIT_VISIBLE_FINAL_SCRIPT = """
            local rowKey = KEYS[1]
            local seqKey = KEYS[2]
            local visibleZset = KEYS[3]
            local logicalPendingZset = KEYS[4]
            local progressPendingZset = KEYS[5]
            local rowJson = ARGV[1]
            local messageId = ARGV[2]
            local pendingMemberPrefix = ARGV[3]
            local existing = redis.call('GET', rowKey)
            if existing then
              return { 'DUPLICATE', existing }
            end
            local seq = redis.call('INCR', seqKey)
            local row = cjson.decode(rowJson)
            row.seq = seq
            row.logicalFinalPublished = false
            row.progressApplied = false
            local encoded = cjson.encode(row)
            redis.call('SET', rowKey, encoded)
            redis.call('ZADD', visibleZset, seq, messageId)
            local pendingMember = pendingMemberPrefix .. tostring(seq)
            local observedAt = row.updateTime or row.completeTime or row.createTime or 0
            redis.call('ZADD', logicalPendingZset, observedAt, pendingMember)
            redis.call('ZADD', progressPendingZset, observedAt, pendingMember)
            return { 'COMMITTED', tostring(seq), encoded }
            """;
    private static final String CLAIM_BARRIER_SCRIPT = """
            local rowKey = KEYS[1]
            local barrierKey = KEYS[2]
            local seq = tonumber(ARGV[1])
            local bitField = ARGV[2]
            local claimToken = ARGV[3]
            local nowMillis = tonumber(ARGV[4])
            local ttlMillis = tonumber(ARGV[5])
            local rowJson = redis.call('GET', rowKey)
            if not rowJson then
              return { 'REJECTED', 'VISIBLE_ROW_MISSING' }
            end
            local row = cjson.decode(rowJson)
            if tonumber(row.seq) ~= seq then
              return { 'REJECTED', 'SEQ_MISMATCH' }
            end
            if row[bitField] == true then
              return { 'ALREADY_DONE' }
            end
            local barrierJson = redis.call('GET', barrierKey)
            if barrierJson then
              local barrier = cjson.decode(barrierJson)
              if barrier.done == true then
                return { 'ALREADY_DONE' }
              end
              if tonumber(barrier.expiresAt or 0) > nowMillis then
                return { 'BUSY', barrier.claimToken or '', tostring(barrier.claimedAt or 0), tostring(barrier.expiresAt or 0) }
              end
            end
            local expiresAt = nowMillis + ttlMillis
            redis.call('SET', barrierKey, cjson.encode({
              claimToken = claimToken,
              claimedAt = nowMillis,
              expiresAt = expiresAt,
              done = false
            }))
            return { 'CLAIMED', claimToken, tostring(nowMillis), tostring(expiresAt) }
            """;
    private static final String MARK_BARRIER_SCRIPT = """
            local rowKey = KEYS[1]
            local barrierKey = KEYS[2]
            local pendingZset = KEYS[3]
            local seq = tonumber(ARGV[1])
            local claimToken = ARGV[2]
            local bitField = ARGV[3]
            local pendingMember = ARGV[4]
            local rowJson = redis.call('GET', rowKey)
            if not rowJson then
              return { 'REJECTED', 'VISIBLE_ROW_MISSING' }
            end
            local row = cjson.decode(rowJson)
            if tonumber(row.seq) ~= seq then
              return { 'REJECTED', 'SEQ_MISMATCH' }
            end
            if row[bitField] == true then
              return { 'ALREADY_DONE' }
            end
            local barrierJson = redis.call('GET', barrierKey)
            if not barrierJson then
              return { 'TOKEN_MISMATCH', 'NO_ACTIVE_CLAIM' }
            end
            local barrier = cjson.decode(barrierJson)
            if barrier.done == true then
              return { 'ALREADY_DONE' }
            end
            if barrier.claimToken ~= claimToken then
              return { 'TOKEN_MISMATCH', 'CLAIM_TOKEN_MISMATCH' }
            end
            row[bitField] = true
            redis.call('SET', rowKey, cjson.encode(row))
            barrier.done = true
            redis.call('SET', barrierKey, cjson.encode(barrier))
            redis.call('ZREM', pendingZset, pendingMember)
            return { 'MARKED' }
            """;

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisTaskResultKeyspace keyspace;
    private final Supplier<Instant> clock;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final long barrierClaimTtlMillis;

    public RedisTaskResultRuntime(String redisUri) {
        this(redisUri, RedisTaskResultKeyspace.DEFAULT_NAMESPACE);
    }

    public RedisTaskResultRuntime(String redisUri, String namespace) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespace,
                Instant::now,
                true,
                DEFAULT_BARRIER_TTL_MILLIS);
    }

    RedisTaskResultRuntime(RedisClient redisClient,
                           String namespace,
                           Supplier<Instant> clock,
                           boolean ownsClient) {
        this(redisClient,
                namespace,
                clock,
                ownsClient,
                DEFAULT_BARRIER_TTL_MILLIS);
    }

    RedisTaskResultRuntime(RedisClient redisClient,
                           String namespace,
                           Supplier<Instant> clock,
                           boolean ownsClient,
                           long barrierClaimTtlMillis) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                new RedisTaskResultKeyspace(namespace),
                clock,
                ownsClient,
                barrierClaimTtlMillis);
    }

    RedisTaskResultRuntime(StatefulRedisConnection<String, String> connection,
                           RedisTaskResultKeyspace keyspace,
                           Supplier<Instant> clock) {
        this(null, connection, keyspace, clock, false, DEFAULT_BARRIER_TTL_MILLIS);
    }

    private RedisTaskResultRuntime(RedisClient redisClient,
                                   StatefulRedisConnection<String, String> connection,
                                   RedisTaskResultKeyspace keyspace,
                                   Supplier<Instant> clock,
                                   boolean ownsClient,
                                   long barrierClaimTtlMillis) {
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ownsClient = ownsClient;
        this.barrierClaimTtlMillis = Math.max(1L, barrierClaimTtlMillis);
    }

    @Override
    public StageResult stageCallback(TaskResultCallbackDraft draft) {
        if (!running.get()) {
            return StageResult.unavailable("result runtime is stopped");
        }
        if (draft == null) {
            return StageResult.rejected("draft must not be null");
        }
        String json = GSON.toJson(draft);
        Boolean inserted = commands.setnx(keyspace.stagedDraft(draft.stageId()), json);
        if (Boolean.TRUE.equals(inserted)) {
            commands.sadd(keyspace.taskStagesSet(draft.taskId()), draft.stageId());
            commands.sadd(keyspace.taskMessageStagesSet(draft.taskId(), draft.messageId()), draft.stageId());
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
            commands.srem(keyspace.taskMessageStagesSet(draft.taskId(), draft.messageId()), stageId);
        }
        return deleted > 0L;
    }

    @Override
    public int discardStagedCallbacksForMessage(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return 0;
        }
        Set<String> stageIds = new LinkedHashSet<>(commands.smembers(keyspace.taskMessageStagesSet(taskId, messageId)));
        int removed = 0;
        for (String stageId : stageIds) {
            if (discardStagedCallback(stageId)) {
                removed++;
            }
        }
        commands.del(keyspace.taskMessageStagesSet(taskId, messageId));
        return removed;
    }

    @Override
    public synchronized CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft) {
        if (!running.get()) {
            return CommitResult.unavailable("result runtime is stopped");
        }
        if (finalDraft == null) {
            return CommitResult.rejected("finalDraft must not be null");
        }
        TaskResultRuntimeRow template = rowFromDraft(finalDraft, 1L, false, false);
        String taskId = finalDraft.taskId();
        String messageId = finalDraft.messageId();
        List<Object> raw = commands.eval(
                COMMIT_VISIBLE_FINAL_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        keyspace.taskVisibleRow(taskId, messageId),
                        keyspace.taskSeqCounter(taskId),
                        keyspace.taskVisibleZset(taskId),
                        keyspace.logicalFinalPendingZset(),
                        keyspace.progressPendingZset()
                },
                GSON.toJson(template),
                messageId,
                pendingMemberPrefix(taskId, messageId)
        );
        String status = stringAt(raw, 0);
        if (STATUS_DUPLICATE.equals(status)) {
            return CommitResult.duplicate(GSON.fromJson(stringAt(raw, 1), TaskResultRuntimeRow.class));
        }
        if (STATUS_COMMITTED.equals(status)) {
            return CommitResult.committed(GSON.fromJson(stringAt(raw, 2), TaskResultRuntimeRow.class));
        }
        return CommitResult.rejected(stringAt(raw, 1));
    }

    @Override
    public List<TaskResultRepairCandidate> scanRepairCandidates(int limit) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        List<TaskResultRepairCandidate> candidates = new ArrayList<>(limit);
        List<String> stageIds = commands.zrange(keyspace.allStagesZset(), 0, Math.max(0, limit * 4L - 1L));
        for (String stageId : stageIds) {
            TaskResultCallbackDraft draft = loadDraft(stageId);
            if (draft == null) {
                commands.zrem(keyspace.allStagesZset(), stageId);
                continue;
            }
            if (getVisibleByMessageId(draft.taskId(), draft.messageId()).isPresent()) {
                continue;
            }
            candidates.add(TaskResultRepairCandidate.missingVisibleFinal(draft));
            if (candidates.size() >= limit) {
                return List.copyOf(candidates);
            }
        }
        collectPendingCandidates(candidates, keyspace.logicalFinalPendingZset(), true, limit);
        collectPendingCandidates(candidates, keyspace.progressPendingZset(), false, limit);
        return List.copyOf(candidates);
    }

    @Override
    public BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq) {
        return claimBarrier(keyspace.logicalFinalBarrier(taskId, messageId, finalSeq),
                taskId,
                messageId,
                finalSeq,
                "logicalFinalPublished");
    }

    @Override
    public BarrierMarkResult markLogicalFinalPublished(String taskId, String messageId, long finalSeq, String claimToken) {
        return markBarrier(keyspace.logicalFinalBarrier(taskId, messageId, finalSeq),
                keyspace.logicalFinalPendingZset(),
                taskId,
                messageId,
                finalSeq,
                claimToken,
                "logicalFinalPublished");
    }

    @Override
    public BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq) {
        return claimBarrier(keyspace.progressBarrier(taskId, messageId, finalSeq),
                taskId,
                messageId,
                finalSeq,
                "progressApplied");
    }

    @Override
    public BarrierMarkResult markProgressApplied(String taskId, String messageId, long finalSeq, String claimToken) {
        return markBarrier(keyspace.progressBarrier(taskId, messageId, finalSeq),
                keyspace.progressPendingZset(),
                taskId,
                messageId,
                finalSeq,
                claimToken,
                "progressApplied");
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
        if (isBlank(json)) {
            return Optional.empty();
        }
        return Optional.of(GSON.fromJson(json, TaskResultRuntimeRow.class));
    }

    @Override
    public long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        long removed = 0L;
        List<String> stageIds = new ArrayList<>(commands.smembers(keyspace.taskStagesSet(taskId)));
        for (String stageId : stageIds) {
            if (discardStagedCallback(stageId)) {
                removed++;
            }
        }
        commands.del(keyspace.taskStagesSet(taskId));
        List<String> messageIds = commands.zrange(keyspace.taskVisibleZset(taskId), 0, -1);
        for (String messageId : messageIds) {
            TaskResultRuntimeRow row = getVisibleByMessageId(taskId, messageId).orElse(null);
            if (row != null) {
                commands.zrem(keyspace.logicalFinalPendingZset(), pendingMember(taskId, messageId, row.seq()));
                commands.zrem(keyspace.progressPendingZset(), pendingMember(taskId, messageId, row.seq()));
                commands.del(
                        keyspace.logicalFinalBarrier(taskId, messageId, row.seq()),
                        keyspace.progressBarrier(taskId, messageId, row.seq())
                );
            }
            if (commands.del(keyspace.taskVisibleRow(taskId, messageId)) > 0L) {
                removed++;
            }
            commands.del(keyspace.taskMessageStagesSet(taskId, messageId));
        }
        commands.del(keyspace.taskVisibleZset(taskId), keyspace.taskSeqCounter(taskId));
        return removed;
    }

    @Override
    public void shutdown() {
        running.set(false);
        closeRedisResources();
    }

    private void collectPendingCandidates(List<TaskResultRepairCandidate> candidates,
                                          String pendingZset,
                                          boolean logicalFinal,
                                          int limit) {
        if (candidates.size() >= limit) {
            return;
        }
        List<String> members = commands.zrange(pendingZset, 0, Math.max(0, limit * 4L - 1L));
        for (String member : members) {
            PendingMember pending = parsePendingMember(member);
            if (pending == null) {
                commands.zrem(pendingZset, member);
                continue;
            }
            Optional<TaskResultRuntimeRow> row = getVisibleByMessageId(pending.taskId(), pending.messageId());
            if (row.isEmpty() || row.get().seq() != pending.seq()) {
                commands.zrem(pendingZset, member);
                continue;
            }
            if (logicalFinal && row.get().logicalFinalPublished()) {
                commands.zrem(pendingZset, member);
                continue;
            }
            if (!logicalFinal && row.get().progressApplied()) {
                commands.zrem(pendingZset, member);
                continue;
            }
            candidates.add(logicalFinal
                    ? TaskResultRepairCandidate.missingLogicalFinalPublish(row.get())
                    : TaskResultRepairCandidate.missingProgressApply(row.get()));
            if (candidates.size() >= limit) {
                return;
            }
        }
    }

    private BarrierClaim claimBarrier(String barrierKey,
                                      String taskId,
                                      String messageId,
                                      long finalSeq,
                                      String bitField) {
        if (!running.get()) {
            return BarrierClaim.unavailable();
        }
        if (isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return BarrierClaim.rejected();
        }
        Instant now = clock.get();
        String claimToken = UUID.randomUUID().toString();
        List<Object> raw = commands.eval(
                CLAIM_BARRIER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        keyspace.taskVisibleRow(taskId, messageId),
                        barrierKey
                },
                Long.toString(finalSeq),
                bitField,
                claimToken,
                Long.toString(now.toEpochMilli()),
                Long.toString(barrierClaimTtlMillis)
        );
        String status = stringAt(raw, 0);
        if (STATUS_CLAIMED.equals(status)) {
            return BarrierClaim.claimed(
                    stringAt(raw, 1),
                    instantAt(raw, 2),
                    instantAt(raw, 3)
            );
        }
        if (STATUS_ALREADY_DONE.equals(status)) {
            return BarrierClaim.alreadyDone();
        }
        if (STATUS_BUSY.equals(status)) {
            return BarrierClaim.busy(
                    stringAt(raw, 1),
                    instantAt(raw, 2),
                    instantAt(raw, 3)
            );
        }
        return BarrierClaim.rejected();
    }

    private BarrierMarkResult markBarrier(String barrierKey,
                                          String pendingZset,
                                          String taskId,
                                          String messageId,
                                          long finalSeq,
                                          String claimToken,
                                          String bitField) {
        if (!running.get()) {
            return BarrierMarkResult.unavailable("result runtime is stopped");
        }
        if (isBlank(taskId) || isBlank(messageId) || finalSeq <= 0) {
            return BarrierMarkResult.rejected("taskId, messageId, and finalSeq are required");
        }
        List<Object> raw = commands.eval(
                MARK_BARRIER_SCRIPT,
                ScriptOutputType.MULTI,
                new String[]{
                        keyspace.taskVisibleRow(taskId, messageId),
                        barrierKey,
                        pendingZset
                },
                Long.toString(finalSeq),
                Objects.toString(claimToken, ""),
                bitField,
                pendingMember(taskId, messageId, finalSeq)
        );
        String status = stringAt(raw, 0);
        if (STATUS_MARKED.equals(status)) {
            return BarrierMarkResult.marked();
        }
        if (STATUS_ALREADY_DONE.equals(status)) {
            return BarrierMarkResult.alreadyDone();
        }
        if (STATUS_TOKEN_MISMATCH.equals(status)) {
            return BarrierMarkResult.tokenMismatch(stringAt(raw, 1));
        }
        if (STATUS_UNAVAILABLE.equals(status)) {
            return BarrierMarkResult.unavailable(stringAt(raw, 1));
        }
        return BarrierMarkResult.rejected(stringAt(raw, 1));
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

    private static String pendingMemberPrefix(String taskId, String messageId) {
        return taskId + PENDING_SEPARATOR + messageId + PENDING_SEPARATOR;
    }

    private static String pendingMember(String taskId, String messageId, long seq) {
        return pendingMemberPrefix(taskId, messageId) + seq;
    }

    private static PendingMember parsePendingMember(String member) {
        if (isBlank(member)) {
            return null;
        }
        String[] parts = member.split(PENDING_SEPARATOR, 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new PendingMember(parts[0], parts[1], Long.parseLong(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stringAt(List<Object> values, int index) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return null;
        }
        Object value = values.get(index);
        return value instanceof byte[] bytes ? new String(bytes) : value.toString();
    }

    private static Instant instantAt(List<Object> values, int index) {
        String raw = stringAt(values, index);
        return isBlank(raw) ? null : Instant.ofEpochMilli(Long.parseLong(raw));
    }

    private static double toScore(Instant instant) {
        return instant == null ? 0D : instant.toEpochMilli();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PendingMember(String taskId, String messageId, long seq) {
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
