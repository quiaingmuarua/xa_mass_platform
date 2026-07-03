package com.xa.mass.task.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.ScoreCandidate;
import com.xa.mass.task.runtime.ScoreCandidateBatch;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

final class RedisScoreBandTaskRuntime implements TaskRuntimeWorkPort,
        TaskRuntimeScorePort,
        TaskRuntimeConvergencePort,
        TaskRuntimeReadPort,
        TaskRuntimeResultWindowReadModel,
        AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final Type LIST_MAP_TYPE = new TypeToken<List<Map<String, Object>>>() {
    }.getType();
    private static final Type LIST_STRING_TYPE = new TypeToken<List<String>>() {
    }.getType();
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final TaskRuntimeRedisKeyCodecV1 keyCodec = new TaskRuntimeRedisKeyCodecV1();
    private final TaskRuntimeRedisKeyspaceV1 keyspace;
    private final TaskRuntimeRedisFrameCodecV1 frameCodec = new TaskRuntimeRedisFrameCodecV1();
    private final LongSupplier clock;

    RedisScoreBandTaskRuntime(RedisClient client, String namespace, LongSupplier clock) {
        this.client = client;
        this.connection = client.connect();
        this.commands = connection.sync();
        this.keyspace = new TaskRuntimeRedisKeyspaceV1(namespace, keyCodec);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    RedisScoreBandTaskRuntime(RedisCommands<String, String> commands, String namespace, LongSupplier clock) {
        this.client = null;
        this.connection = null;
        this.commands = commands;
        this.keyspace = new TaskRuntimeRedisKeyspaceV1(namespace, keyCodec);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    TaskRuntimeRedisKeyspaceV1 keyspace() {
        return keyspace;
    }

    @Override
    public AppendBatchOutcome appendBacklog(String taskId, List<AppendItemInput> frames, int maxBatchSize) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must be non-empty");
        }
        if (frames.size() > Math.max(1, maxBatchSize)) {
            return AppendBatchOutcome.rejectedBeforeRuntime(taskId, "batch exceeds maxAppendBatchSize");
        }
        String[] encoded = frames.stream()
                .map(frame -> frameCodec.encodeBacklogFrame(taskId, frame, clock.getAsLong()))
                .toArray(String[]::new);
        commands.rpush(keyspace.taskBacklogKey(taskId), encoded);
        return AppendBatchOutcome.allAccepted(taskId, frames.stream().map(AppendItemInput::messageId).toList());
    }

    @Override
    public ClaimReadyOutcome claimBacklog(ScoreCandidate candidate,
                                          List<WorkerReservationEvidence> reservations,
                                          int maxItems,
                                          long leaseMillis,
                                          long nowMillis) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        if (reservations == null || reservations.isEmpty()) {
            throw new IllegalArgumentException("reservations must be non-empty");
        }
        if (!candidate.observedScore().isSchedulableBand()) {
            return new ClaimReadyOutcome(candidate.taskId(), List.of(), "score candidate is not dispatch-visible");
        }
        String taskId = candidate.taskId();
        int limit = Math.max(1, maxItems);
        List<String> leaseTokens = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            leaseTokens.add(UUID.randomUUID().toString());
        }
        String claimedJson = commands.eval(
                RedisScoreBandTaskRuntimeScripts.CLAIM_BACKLOG,
                ScriptOutputType.VALUE,
                new String[]{
                        keyspace.taskBacklogKey(taskId),
                        keyspace.taskRuntimeStateKey(taskId),
                        keyspace.taskMetaKey(taskId),
                        keyspace.taskScoreKey(candidate.laneKey())
                },
                taskId,
                candidate.laneKey(),
                Long.toString(candidate.runtimeEpoch().epoch()),
                candidate.runtimeEpoch().fenceToken() == null ? "" : candidate.runtimeEpoch().fenceToken(),
                Long.toString(candidate.observedScore().score()),
                Long.toString(TaskScoreV1.MAINT_ACTIVE),
                Long.toString(TaskScoreV1.TIME_SCORE_FLOOR),
                Long.toString(nowMillis),
                Long.toString(Math.max(1L, leaseMillis)),
                Integer.toString(limit),
                encodeReservations(reservations),
                GSON.toJson(leaseTokens));
        return claimOutcomeFrom(taskId, claimedJson);
    }

    @Override
    public void putRuntimeMeta(TaskRuntimeMetaV1 meta) {
        commands.sadd(keyspace.lanesKey(), meta.laneKey());
        var fields = new LinkedHashMap<String, String>();
        fields.put("schemaVersion", "1");
        fields.put("taskId", meta.taskId());
        fields.put("laneBucketId", meta.laneKey());
        fields.put("runtimeGate", meta.runtimeGate().name());
        fields.put("runtimeEpoch", Long.toString(meta.runtimeEpoch().epoch()));
        fields.put("fenceToken", meta.runtimeEpoch().fenceToken() == null ? "" : meta.runtimeEpoch().fenceToken());
        fields.put("retryMode", meta.resultPolicy().retryMode().name());
        fields.put("maxRetryCount", Integer.toString(meta.resultPolicy().maxRetryCount()));
        fields.put("retryDelayMillis", Long.toString(meta.resultPolicy().retryDelayMillis()));
        fields.put("retryPolicyVersion", Long.toString(meta.resultPolicy().retryPolicyVersion()));
        fields.put("retryExpiredLeaseFromAnyActiveState", Boolean.toString(meta.resultPolicy().retryExpiredLeaseFromAnyActiveState()));
        fields.put("expiredLeaseFinalizesAsFailure", Boolean.toString(meta.resultPolicy().expiredLeaseFinalizesAsFailure()));
        fields.put("finalResultRetentionMillis", Long.toString(meta.resultPolicy().finalResultRetentionMillis()));
        fields.put("updatedAtMillis", Long.toString(clock.getAsLong()));
        commands.hset(keyspace.taskMetaKey(meta.taskId()), fields);
    }

    @Override
    public void setTaskScore(String taskId, String laneKey, RuntimeEpoch epoch, TaskScoreV1 score) {
        commands.sadd(keyspace.lanesKey(), laneKey);
        commands.zadd(
                keyspace.taskScoreKey(laneKey),
                score == null ? TaskScoreV1.TIME_SCORE_FLOOR : score.score(),
                keyCodec.encodeSegment(taskId));
    }

    @Override
    public void removeTaskScore(String taskId, String laneKey, RuntimeEpoch epoch) {
        commands.zrem(keyspace.taskScoreKey(laneKey), keyCodec.encodeSegment(taskId));
    }

    @Override
    public Optional<TaskScoreV1> taskScore(String taskId, String laneKey) {
        Double score = commands.zscore(keyspace.taskScoreKey(laneKey), keyCodec.encodeSegment(taskId));
        return score == null ? Optional.empty() : Optional.of(new TaskScoreV1(score.longValue()));
    }

    @Override
    public Optional<ScoreCandidate> scoreCandidate(String taskId, String laneKey) {
        String encodedTaskId = keyCodec.encodeSegment(taskId);
        Double score = commands.zscore(keyspace.taskScoreKey(laneKey), encodedTaskId);
        if (score == null || score.longValue() < TaskScoreV1.TIME_SCORE_FLOOR) {
            return Optional.empty();
        }
        Map<String, String> meta = commands.hgetall(keyspace.taskMetaKey(taskId));
        return Optional.of(new ScoreCandidate(
                taskId,
                laneKey,
                RuntimeEpoch.of(taskId, longValue(meta.get("runtimeEpoch")), meta.get("fenceToken")),
                new TaskScoreV1(score.longValue())));
    }

    @Override
    public ScoreCandidateBatch discoverSchedulable(String laneKey, long maxScore, int limit) {
        var encodedTaskIds = commands.zrangebyscore(
                keyspace.taskScoreKey(laneKey),
                Range.create((double) TaskScoreV1.TIME_SCORE_FLOOR, (double) maxScore),
                Limit.create(0, Math.max(1, limit)));
        var candidates = new ArrayList<ScoreCandidate>();
        for (var encodedTaskId : encodedTaskIds) {
            String taskId = keyCodec.decodeSegment(encodedTaskId);
            Map<String, String> meta = commands.hgetall(keyspace.taskMetaKey(taskId));
            Double score = commands.zscore(keyspace.taskScoreKey(laneKey), encodedTaskId);
            candidates.add(new ScoreCandidate(
                    taskId,
                    laneKey,
                    RuntimeEpoch.of(taskId, longValue(meta.get("runtimeEpoch")), meta.get("fenceToken")),
                    new TaskScoreV1(score == null ? 0L : score.longValue())));
        }
        return new ScoreCandidateBatch(candidates);
    }

    @Override
    public List<String> promoteDueRetries(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
        var promoted = new ArrayList<String>();
        for (String encodedTaskId : laneMembers(laneKey, taskLimit)) {
            String taskId = keyCodec.decodeSegment(encodedTaskId);
            String promotedJson = commands.eval(
                    RedisScoreBandTaskRuntimeScripts.PROMOTE_DUE_RETRIES,
                    ScriptOutputType.VALUE,
                    new String[]{
                            keyspace.taskRetryScoreKey(taskId),
                            keyspace.taskRetryItemKey(taskId),
                            keyspace.taskBacklogKey(taskId),
                            keyspace.taskScoreKey(laneKey)
                    },
                    Long.toString(nowMillis),
                    Integer.toString(Math.max(1, itemLimit)),
                    encodedTaskId,
                    Long.toString(TaskScoreV1.dueAt(nowMillis).score()));
            for (String encodedMessageId : decodeStringList(promotedJson)) {
                promoted.add(keyCodec.decodeSegment(encodedMessageId));
            }
        }
        return List.copyOf(promoted);
    }

    @Override
    public List<ActiveLeaseRepairCandidate> scanExpiredLeases(String laneKey, long nowMillis, int taskLimit, int itemLimit) {
        var repaired = new ArrayList<ActiveLeaseRepairCandidate>();
        for (String encodedTaskId : laneMembers(laneKey, taskLimit)) {
            String taskId = keyCodec.decodeSegment(encodedTaskId);
            for (String encodedState : commands.hvals(keyspace.taskRuntimeStateKey(taskId))) {
                if (repaired.size() >= Math.max(1, itemLimit)) {
                    return List.copyOf(repaired);
                }
                Map<String, Object> state = decode(encodedState);
                if (longValue(state.get("leaseExpireAtMillis")) > nowMillis) {
                    continue;
                }
                repaired.add(toRepairCandidate(taskId, state));
            }
        }
        return List.copyOf(repaired);
    }

    @Override
    public MessageFinalityOutcome applyResult(RuntimeResultFact fact) {
        TaskRuntimeResultPolicyV1 policy = readResultPolicy(fact.taskId());
        boolean retryAllowed = !fact.success()
                && policy.maxRetryCount() > 0
                && fact.attemptNo() <= policy.maxRetryCount();
        long retryAtMillis = fact.observedAtMillis() + policy.retryDelayMillis();
        if (policy.retryMode() != RetryMode.DUE_TIME || retryAtMillis <= fact.observedAtMillis()) {
            retryAtMillis = fact.observedAtMillis();
        }
        long retentionMillis = policy.finalResultRetentionMillis();
        long finalExpiresAt = retentionMillis <= 0L ? 0L : fact.observedAtMillis() + retentionMillis;
        return applyResult(
                fact.taskId(),
                fact.messageId(),
                fact.leaseToken(),
                fact.workerId(),
                fact.attemptNo(),
                fact.source(),
                fact.success(),
                fact.resultPayloadJson(),
                fact.failureReason(),
                fact.runtimeEpoch(),
                fact.observedAtMillis(),
                retryAllowed,
                retryAtMillis,
                finalExpiresAt);
    }

    private MessageFinalityOutcome applyResult(String taskId,
                                               String messageId,
                                               String leaseToken,
                                               String workerId,
                                               int attemptNo,
                                               ResultApplySource source,
                                               boolean success,
                                               Map<String, Object> resultPayloadJson,
                                               String failureReason,
                                               RuntimeEpoch runtimeEpoch,
                                               long observedAtMillis,
                                               boolean retryAllowed,
                                               long retryAtMillis,
                                               long finalExpiresAt) {
        String encodedMessageId = keyCodec.encodeSegment(messageId);
        String laneKey = laneKeyForTask(taskId);
        String outcomeJson = commands.eval(
                RedisScoreBandTaskRuntimeScripts.APPLY_RESULT,
                ScriptOutputType.VALUE,
                new String[]{
                        keyspace.taskRuntimeStateKey(taskId),
                        keyspace.taskResultKey(taskId),
                        keyspace.taskRetryScoreKey(taskId),
                        keyspace.taskRetryItemKey(taskId),
                        keyspace.taskBacklogKey(taskId),
                        keyspace.taskScoreKey(laneKey)
                },
                encodedMessageId,
                taskId,
                messageId,
                leaseToken,
                workerId,
                Integer.toString(attemptNo),
                source.name(),
                Boolean.toString(success),
                GSON.toJson(resultPayloadJson == null ? Map.of() : resultPayloadJson),
                failureReason == null ? "" : failureReason,
                Long.toString(observedAtMillis),
                Boolean.toString(retryAllowed),
                Long.toString(retryAtMillis),
                Long.toString(finalExpiresAt),
                keyCodec.encodeSegment(taskId),
                Long.toString(TaskScoreV1.MAINT_ACTIVE),
                Long.toString(TaskScoreV1.dueAt(Math.max(observedAtMillis, retryAtMillis)).score()));
        Map<String, Object> outcome = decode(outcomeJson);
        String status = stringValue(outcome.get("status"));
        if ("LOGICAL_FINAL".equals(status)) {
            return MessageFinalityOutcome.logicalFinal(
                    taskId,
                    messageId,
                    attemptNo,
                    longValue(outcome.get("finalResultExpiresAtMillis")));
        }
        if ("RETRY_SCHEDULED".equals(status)) {
            return MessageFinalityOutcome.retryScheduled(
                    taskId,
                    messageId,
                    attemptNo,
                    longValue(outcome.get("retryAtMillis")),
                    stringValue(outcome.get("reason")));
        }
        return MessageFinalityOutcome.duplicateOrLate(
                taskId,
                messageId,
                attemptNo,
                stringValue(outcome.get("reason")));
    }

    @Override
    public ResultCorrelationSnapshot resultCorrelation(String taskId, String messageId) {
        String activeJson = commands.hget(keyspace.taskRuntimeStateKey(taskId), keyCodec.encodeSegment(messageId));
        if (activeJson == null) {
            return ResultCorrelationSnapshot.missing(taskId, messageId);
        }
        Map<String, Object> active = decode(activeJson);
        return new ResultCorrelationSnapshot(
                taskId,
                messageId,
                stringValue(active.get("leaseToken")),
                stringValue(active.get("workerId")),
                intValue(active.get("attemptNo")),
                true);
    }

    boolean hasActiveResultState(String taskId, String messageId) {
        return commands.hexists(keyspace.taskRuntimeStateKey(taskId), keyCodec.encodeSegment(messageId));
    }

    @Override
    public boolean closeIfDrained(String taskId, String laneKey, RuntimeEpoch epoch) {
        Long closed = commands.eval(
                RedisScoreBandTaskRuntimeScripts.CLOSE_IF_DRAINED,
                ScriptOutputType.INTEGER,
                new String[]{
                        keyspace.taskBacklogKey(taskId),
                        keyspace.taskRetryScoreKey(taskId),
                        keyspace.taskRetryItemKey(taskId),
                        keyspace.taskRuntimeStateKey(taskId),
                        keyspace.taskScoreKey(laneKey)
                },
                keyCodec.encodeSegment(taskId));
        return closed != null && closed == 1L;
    }

    @Override
    public void discardRuntime(String taskId, String laneKey, RuntimeEpoch epoch, String reason) {
        commands.eval(
                RedisScoreBandTaskRuntimeScripts.DISCARD_RUNTIME,
                ScriptOutputType.VALUE,
                new String[]{
                        keyspace.taskBacklogKey(taskId),
                        keyspace.taskRetryScoreKey(taskId),
                        keyspace.taskRetryItemKey(taskId),
                        keyspace.taskRuntimeStateKey(taskId),
                        keyspace.taskResultKey(taskId),
                        keyspace.taskMetaKey(taskId),
                        keyspace.taskScoreKey(laneKey)
                },
                keyCodec.encodeSegment(taskId));
    }

    @Override
    public void discardWork(String taskId, RuntimeEpoch epoch, String reason) {
        commands.eval(
                RedisScoreBandTaskRuntimeScripts.DISCARD_WORK,
                ScriptOutputType.VALUE,
                new String[]{
                        keyspace.taskBacklogKey(taskId),
                        keyspace.taskRetryScoreKey(taskId),
                        keyspace.taskRetryItemKey(taskId),
                        keyspace.taskRuntimeStateKey(taskId)
                });
    }

    @Override
    public FinalResultWindow readFinalResults(FinalResultReadRequest request) {
        var rows = new ArrayList<FinalResultRow>();
        for (String resultJson : commands.hvals(keyspace.taskResultKey(request.taskId()))) {
            if (rows.size() >= request.limit()) {
                break;
            }
            rows.add(toFinalResultRow(request.taskId(), resultJson));
        }
        return new FinalResultWindow(request.taskId(), rows, request.afterSeq(), false, rows.size());
    }

    @Override
    public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
        String result = commands.hget(keyspace.taskResultKey(taskId), keyCodec.encodeSegment(messageId));
        return result == null ? Optional.empty() : Optional.of(toFinalResultRow(taskId, result));
    }

    @Override
    public ActiveTaskWorkSnapshot activeWorkForTask(String taskId, int limit) {
        var active = new ArrayList<ActiveLeaseRepairCandidate>();
        for (String encodedState : commands.hvals(keyspace.taskRuntimeStateKey(taskId))) {
            if (active.size() >= Math.max(1, limit)) {
                break;
            }
            active.add(toRepairCandidate(taskId, decode(encodedState)));
        }
        return new ActiveTaskWorkSnapshot(taskId, active);
    }

    @Override
    public TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
        long backlog = commands.llen(keyspace.taskBacklogKey(taskId));
        long delayed = commands.zcard(keyspace.taskRetryScoreKey(taskId));
        long active = commands.hlen(keyspace.taskRuntimeStateKey(taskId));
        long success = 0L;
        long failed = 0L;
        for (String resultJson : commands.hvals(keyspace.taskResultKey(taskId))) {
            if (booleanValue(decode(resultJson).get("success"))) {
                success++;
            } else {
                failed++;
            }
        }
        return new TaskRuntimeProgressSnapshot(
                taskId,
                backlog + delayed + active + success + failed,
                backlog,
                delayed,
                active,
                success,
                failed,
                0L);
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    private List<String> laneMembers(String laneKey, int limit) {
        return commands.zrangebyscore(
                keyspace.taskScoreKey(laneKey),
                Range.create(0.0, Double.MAX_VALUE),
                Limit.create(0, Math.max(1, limit)));
    }

    private String laneKeyForTask(String taskId) {
        String laneKey = commands.hget(keyspace.taskMetaKey(taskId), "laneBucketId");
        return laneKey == null || laneKey.isBlank() ? "default" : laneKey;
    }

    private TaskRuntimeResultPolicyV1 readResultPolicy(String taskId) {
        Map<String, String> meta = commands.hgetall(keyspace.taskMetaKey(taskId));
        if (meta == null || meta.isEmpty()) {
            return TaskRuntimeResultPolicyV1.defaultPolicy();
        }
        return new TaskRuntimeResultPolicyV1(
                retryModeValue(meta.get("retryMode")),
                intValue(meta.get("maxRetryCount")),
                longValue(meta.get("retryDelayMillis")),
                longValue(meta.get("retryPolicyVersion")),
                booleanValue(meta.get("retryExpiredLeaseFromAnyActiveState")),
                booleanValue(meta.get("expiredLeaseFinalizesAsFailure")),
                longValue(meta.get("finalResultRetentionMillis")));
    }

    private ActiveLeaseRepairCandidate toRepairCandidate(String taskId, Map<String, Object> state) {
        return new ActiveLeaseRepairCandidate(
                taskId,
                stringValue(state.get("messageId")),
                stringValue(state.get("leaseToken")),
                stringValue(state.get("workerId")),
                stringValue(state.get("workerGroupId")),
                stringValue(state.get("batchId")),
                stringValue(state.get("workerReservationToken")),
                longObjectValue(state.get("scoreBandClaimScore")),
                intValue(state.get("attemptNo")),
                longValue(state.get("leaseExpireAtMillis")));
    }

    private FinalResultRow toFinalResultRow(String taskId, String resultJson) {
        Map<String, Object> result = decode(resultJson);
        return new FinalResultRow(
                taskId,
                stringValue(result.get("messageId")),
                0L,
                intValue(result.get("attemptNo")),
                stringValue(result.get("workerId")),
                stringValue(result.get("batchId")),
                ResultApplySource.WORKER_RESULT,
                booleanValue(result.get("success")),
                payloadValue(result.get("resultPayloadJson")),
                stringValue(result.get("errorMessage")),
                longValue(result.get("completedAtMillis")),
                longValue(result.get("expiresAtMillis")));
    }

    private String encodeReservations(List<WorkerReservationEvidence> reservations) {
        var encoded = new ArrayList<Map<String, Object>>();
        for (WorkerReservationEvidence reservation : reservations) {
            var row = new LinkedHashMap<String, Object>();
            row.put("workerId", reservation.workerId());
            row.put("workerGroupId", reservation.workerGroupId());
            row.put("reservationToken", reservation.reservationToken());
            row.put("dispatchTargetRef", reservation.dispatchTargetRef());
            row.put("batchId", reservation.batchId());
            row.put("scoreBandClaimScore", reservation.scoreBandClaimScore());
            encoded.add(row);
        }
        return GSON.toJson(encoded);
    }

    private ClaimReadyOutcome claimOutcomeFrom(String taskId, String json) {
        Map<String, Object> outcome = decode(json);
        var claimed = claimedItemsFrom(outcome.get("claimed"));
        String reason = stringValue(outcome.get("reason"));
        if (claimed.isEmpty() && (reason == null || reason.isBlank())) {
            reason = stringValue(outcome.get("status"));
        }
        return new ClaimReadyOutcome(taskId, claimed, reason == null ? "" : reason);
    }

    private List<ClaimedWorkItem> claimedItemsFrom(Object rowsValue) {
        if (!(rowsValue instanceof List<?> rows) || rows.isEmpty()) {
            return List.of();
        }
        var claimed = new ArrayList<ClaimedWorkItem>(rows.size());
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            var row = new LinkedHashMap<String, Object>();
            raw.forEach((key, value) -> row.put(String.valueOf(key), value));
            claimed.add(new ClaimedWorkItem(
                    stringValue(row.get("taskId")),
                    stringValue(row.get("messageId")),
                    stringValue(row.get("eventCode")),
                    payloadValue(row.get("payloadJson")),
                    stringValue(row.get("payloadRef")),
                    stringValue(row.get("leaseToken")),
                    stringValue(row.get("workerReservationToken")),
                    longObjectValue(row.get("scoreBandClaimScore")),
                    stringValue(row.get("workerId")),
                    stringValue(row.get("workerGroupId")),
                    stringValue(row.get("batchId")),
                    intValue(row.get("attemptNo")),
                    longValue(row.get("leaseExpireAtMillis"))));
        }
        return claimed;
    }

    private List<ClaimedWorkItem> claimedItemsFrom(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = GSON.fromJson(json, LIST_MAP_TYPE);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        var claimed = new ArrayList<ClaimedWorkItem>(rows.size());
        for (Map<String, Object> row : rows) {
            claimed.add(new ClaimedWorkItem(
                    stringValue(row.get("taskId")),
                    stringValue(row.get("messageId")),
                    stringValue(row.get("eventCode")),
                    payloadValue(row.get("payloadJson")),
                    stringValue(row.get("payloadRef")),
                    stringValue(row.get("leaseToken")),
                    stringValue(row.get("workerReservationToken")),
                    longObjectValue(row.get("scoreBandClaimScore")),
                    stringValue(row.get("workerId")),
                    stringValue(row.get("workerGroupId")),
                    stringValue(row.get("batchId")),
                    intValue(row.get("attemptNo")),
                    longValue(row.get("leaseExpireAtMillis"))));
        }
        return claimed;
    }

    private List<String> decodeStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<String> decoded = GSON.fromJson(json, LIST_STRING_TYPE);
        return decoded == null ? List.of() : decoded;
    }

    private Map<String, Object> decode(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> decoded = GSON.fromJson(json, MAP_TYPE);
        return decoded == null ? Map.of() : decoded;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var typed = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> typed.put(String.valueOf(key), item));
            return typed;
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> payloadValue(Object value) {
        return mapValue(value);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Long longObjectValue(Object value) {
        if (value == null) {
            return null;
        }
        return longValue(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static RetryMode retryModeValue(String value) {
        if (value == null || value.isBlank()) {
            return RetryMode.FAST_READY;
        }
        return RetryMode.valueOf(value);
    }
}
