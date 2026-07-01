package com.xa.mass.task.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.task.runtime.ActiveLeaseRepairBatch;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ActiveTaskWorkQuery;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.ActiveWorkQuery;
import com.xa.mass.task.runtime.ActiveWorkSnapshot;
import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.DiscardTaskRuntimeCommand;
import com.xa.mass.task.runtime.DiscardTaskRuntimeOutcome;
import com.xa.mass.task.runtime.DiscardTaskWorkCommand;
import com.xa.mass.task.runtime.DiscardTaskWorkOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.PollActiveLeaseRepairCommand;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerDiscoveryOutcome;
import com.xa.mass.task.runtime.SchedulerEligibilityPolicy;
import com.xa.mass.task.runtime.SchedulerTaskCandidate;
import com.xa.mass.task.runtime.TaskRuntimeAppendPort;
import com.xa.mass.task.runtime.TaskRuntimeClaimPort;
import com.xa.mass.task.runtime.TaskRuntimeDiscardPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressPort;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeRepairPort;
import com.xa.mass.task.runtime.TaskRuntimeResultPort;
import com.xa.mass.task.runtime.TaskRuntimeSchedulerPort;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class RedisTaskRuntime implements TaskRuntimeAppendPort,
        TaskRuntimeSchedulerPort,
        TaskRuntimeClaimPort,
        TaskRuntimeResultPort,
        TaskRuntimeRepairPort,
        TaskRuntimeProgressPort,
        TaskRuntimeReadPort,
        TaskRuntimeDiscardPort,
        AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String ALL_ACCEPTED = "ALL_ACCEPTED";
    private static final String REJECTED_PREFIX = "REJECTED:";

    private static final String APPEND_SCRIPT = String.join("\n",
            "local ids = KEYS[1]",
            "local ready = KEYS[2]",
            "local delayed = KEYS[3]",
            "local tasks = KEYS[4]",
            "local dirty = KEYS[5]",
            "local taskId = ARGV[1]",
            "local maxBacklog = tonumber(ARGV[2])",
            "local count = tonumber(ARGV[3])",
            "local existing = 0",
            "local offset = 4",
            "for i = 1, count do",
            "  local messageId = ARGV[offset]",
            "  if redis.call('SISMEMBER', ids, messageId) == 1 then existing = existing + 1 end",
            "  offset = offset + 2",
            "end",
            "if existing == count then return 'ALL_ACCEPTED' end",
            "if existing > 0 then return 'REJECTED:batch mixes existing and new items' end",
            "local backlog = redis.call('LLEN', ready) + redis.call('ZCARD', delayed)",
            "if maxBacklog >= 0 and backlog + count > maxBacklog then return 'REJECTED:ready backlog is full' end",
            "offset = 4",
            "for i = 1, count do",
            "  local messageId = ARGV[offset]",
            "  local frame = ARGV[offset + 1]",
            "  redis.call('SADD', ids, messageId)",
            "  redis.call('RPUSH', ready, frame)",
            "  offset = offset + 2",
            "end",
            "redis.call('SADD', tasks, taskId)",
            "redis.call('SADD', dirty, taskId)",
            "return 'ALL_ACCEPTED'"
    );

    private static final String PROMOTE_DUE_SCRIPT = String.join("\n",
            "local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])",
            "for i, frame in ipairs(due) do",
            "  redis.call('ZREM', KEYS[1], frame)",
            "  redis.call('RPUSH', KEYS[2], frame)",
            "  redis.call('SADD', KEYS[3], ARGV[3])",
            "end",
            "return #due"
    );

    private static final String CLAIM_SCRIPT = String.join("\n",
            "local ready = KEYS[1]",
            "local active = KEYS[2]",
            "local dirty = KEYS[3]",
            "local namespace = ARGV[1]",
            "local taskId = ARGV[2]",
            "local expectedEpoch = ARGV[3]",
            "local expectedFence = ARGV[4]",
            "local leaseExpireAt = ARGV[5]",
            "local maxItems = tonumber(ARGV[6])",
            "local reservationCount = tonumber(ARGV[7])",
            "local first = redis.call('LINDEX', ready, 0)",
            "if not first then return {'REJECTED:no ready work'} end",
            "local firstMessageId, firstEpoch, firstFence = string.match(first, '^([^|]*)|([^|]*)|([^|]*)|')",
            "if firstEpoch ~= expectedEpoch or firstFence ~= expectedFence then return {'REJECTED:runtime epoch mismatch'} end",
            "local readyCount = redis.call('LLEN', ready)",
            "local count = math.min(maxItems, readyCount)",
            "local result = {}",
            "local reservationOffset = 8",
            "local leaseTokenOffset = reservationOffset + (reservationCount * 6)",
            "for i = 1, count do",
            "  local frame = redis.call('LPOP', ready)",
            "  if not frame then break end",
            "  local reservationIndex = ((i - 1) % reservationCount)",
            "  local offset = reservationOffset + (reservationIndex * 6)",
            "  local workerId = ARGV[offset]",
            "  local workerGroupId = ARGV[offset + 1]",
            "  local reservationToken = ARGV[offset + 2]",
            "  local scoreBandClaimScore = ARGV[offset + 3]",
            "  local dispatchTarget = ARGV[offset + 4]",
            "  local batchId = ARGV[offset + 5]",
            "  local leaseToken = ARGV[leaseTokenOffset + i - 1]",
            "  local messageId = string.match(frame, '^([^|]*)|')",
            "  local activeFrame = frame .. '|' .. workerId .. '|' .. workerGroupId .. '|' .. reservationToken .. '|' .. scoreBandClaimScore .. '|' .. dispatchTarget .. '|' .. batchId .. '|' .. leaseToken .. '|' .. leaseExpireAt",
            "  redis.call('HSET', active, messageId, activeFrame)",
            "  redis.call('SADD', namespace .. ':worker:' .. workerId .. ':active', taskId .. '|' .. messageId)",
            "  table.insert(result, activeFrame)",
            "end",
            "if redis.call('LLEN', ready) == 0 then redis.call('SREM', dirty, taskId) end",
            "return result"
    );

    private static final String APPLY_RESULT_SCRIPT = String.join("\n",
            "local active = KEYS[1]",
            "local ready = KEYS[2]",
            "local delayed = KEYS[3]",
            "local finalRows = KEYS[4]",
            "local finalOrder = KEYS[5]",
            "local finalSeq = KEYS[6]",
            "local dirty = KEYS[7]",
            "local workerActive = KEYS[8]",
            "local messageId = ARGV[1]",
            "local expectedActive = ARGV[2]",
            "local action = ARGV[3]",
            "local retryFrame = ARGV[4]",
            "local retryAt = ARGV[5]",
            "local finalRow = ARGV[6]",
            "local taskId = ARGV[7]",
            "local activeMember = ARGV[8]",
            "local current = redis.call('HGET', active, messageId)",
            "if not current then",
            "  if redis.call('HEXISTS', finalRows, messageId) == 1 then return 'DUPLICATE' end",
            "  return 'REJECTED:no active lease'",
            "end",
            "if current ~= expectedActive then return 'REJECTED:active lease correlation mismatch' end",
            "redis.call('HDEL', active, messageId)",
            "redis.call('SREM', workerActive, activeMember)",
            "if action == 'RETRY_READY' then",
            "  redis.call('RPUSH', ready, retryFrame)",
            "  redis.call('SADD', dirty, taskId)",
            "  return 'RETRY'",
            "elseif action == 'RETRY_DELAYED' then",
            "  redis.call('ZADD', delayed, retryAt, retryFrame)",
            "  return 'RETRY'",
            "else",
            "  local seq = redis.call('INCR', finalSeq)",
            "  redis.call('HSET', finalRows, messageId, finalRow)",
            "  redis.call('ZADD', finalOrder, seq, messageId)",
            "  return 'FINAL'",
            "end"
    );

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final String namespace;
    private final LongSupplier clock;

    public RedisTaskRuntime(RedisClient client, String namespace) {
        this(client, namespace, System::currentTimeMillis);
    }

    public RedisTaskRuntime(RedisClient client, String namespace, LongSupplier clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = requireText(namespace, "namespace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connection = client.connect();
        this.commands = connection.sync();
    }

    @Override
    public AppendBatchOutcome appendBatch(AppendBatchCommand command) {
        var taskId = encode(command.taskId());
        var args = new ArrayList<String>();
        args.add(taskId);
        args.add(Long.toString(command.admissionPolicy().maxReadyBacklogItems()));
        args.add(Integer.toString(command.items().size()));
        for (var item : command.items()) {
            var messageId = encode(item.messageId());
            args.add(messageId);
            args.add(ReadyFrame.initial(
                    messageId,
                    item.eventCode(),
                    command.runtimeEpoch(),
                    item.payloadJson(),
                    item.payloadRef()).encode());
        }
        String result = commands.eval(APPEND_SCRIPT, ScriptOutputType.VALUE, new String[]{
                idsKey(taskId),
                readyKey(taskId),
                delayedKey(taskId),
                tasksKey(),
                dirtyKey()
        }, args.toArray(String[]::new));
        if (ALL_ACCEPTED.equals(result)) {
            return AppendBatchOutcome.allAccepted(command.taskId(), command.items().stream().map(item -> item.messageId()).toList());
        }
        var reason = result != null && result.startsWith(REJECTED_PREFIX)
                ? result.substring(REJECTED_PREFIX.length())
                : "append rejected";
        return AppendBatchOutcome.rejectedBeforeRuntime(command.taskId(), reason);
    }

    @Override
    public void updateTaskEligibility(UpdateSchedulerEligibilityCommand command) {
        var taskId = encode(command.taskId());
        var policy = command.eligibilityPolicy();
        commands.hset(eligibilityKey(taskId), Map.of(
                "runtimeGate", policy.runtimeGate().name(),
                "dispatchLane", policy.dispatchLane(),
                "nextEligibleAtMillis", Long.toString(policy.nextEligibleAtMillis()),
                "positiveMatchDelayMillis", Long.toString(policy.positiveMatchDelayMillis()),
                "emptyMatchDelayMillis", Long.toString(policy.emptyMatchDelayMillis()),
                "contentionRecheckDelayMillis", Long.toString(policy.contentionRecheckDelayMillis()),
                "epoch", Long.toString(command.runtimeEpoch().epoch()),
                "fence", encodeNullable(command.runtimeEpoch().fenceToken())
        ));
        commands.sadd(tasksKey(), taskId);
        commands.sadd(dirtyKey(), taskId);
    }

    @Override
    public SchedulerDiscoveryOutcome discoverEligibleTasks(SchedulerDiscoveryCommand command) {
        var candidates = new ArrayList<SchedulerTaskCandidate>();
        for (var taskId : commands.smembers(tasksKey())) {
            promoteDue(taskId, command.nowMillis());
            if (candidates.size() >= command.limit()) {
                break;
            }
            if (commands.llen(readyKey(taskId)) <= 0) {
                commands.srem(dirtyKey(), taskId);
                continue;
            }
            var eligibility = readEligibility(taskId);
            if (eligibility.policy().runtimeGate() != RuntimeGate.OPEN
                    || eligibility.policy().nextEligibleAtMillis() > command.nowMillis()) {
                continue;
            }
            candidates.add(new SchedulerTaskCandidate(
                    decode(taskId),
                    eligibility.runtimeEpoch(),
                    eligibility.policy().nextEligibleAtMillis()));
        }
        return new SchedulerDiscoveryOutcome(candidates);
    }

    @Override
    public void markTaskDirty(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            commands.sadd(dirtyKey(), encode(taskId));
        }
    }

    @Override
    public ClaimReadyOutcome claimReady(ClaimReadyCommand command) {
        var taskId = encode(command.taskId());
        var args = new ArrayList<String>();
        args.add(namespace);
        args.add(taskId);
        args.add(Long.toString(command.leasePolicy().expectedRuntimeEpoch().epoch()));
        args.add(encodeNullable(command.leasePolicy().expectedRuntimeEpoch().fenceToken()));
        args.add(Long.toString(clock.getAsLong() + command.leasePolicy().leaseMillis()));
        args.add(Integer.toString(command.leasePolicy().maxItems()));
        args.add(Integer.toString(command.workerReservations().size()));
        for (var reservation : command.workerReservations()) {
            args.add(encode(reservation.workerId()));
            args.add(encode(reservation.workerGroupId()));
            args.add(encode(reservation.reservationToken()));
            args.add(encodeNullableLong(reservation.scoreBandClaimScore()));
            args.add(encodeNullable(reservation.dispatchTargetRef()));
            args.add(encodeNullable(reservation.batchId()));
        }
        for (int i = 0; i < command.leasePolicy().maxItems(); i++) {
            args.add(encode(UUID.randomUUID().toString()));
        }
        List<String> result = commands.eval(CLAIM_SCRIPT, ScriptOutputType.MULTI, new String[]{
                readyKey(taskId),
                activeKey(taskId),
                dirtyKey()
        }, args.toArray(String[]::new));
        if (result.isEmpty()) {
            return new ClaimReadyOutcome(command.taskId(), List.of(), "no ready work");
        }
        if (result.getFirst().startsWith(REJECTED_PREFIX)) {
            return new ClaimReadyOutcome(command.taskId(), List.of(), result.getFirst().substring(REJECTED_PREFIX.length()));
        }
        return new ClaimReadyOutcome(command.taskId(), result.stream().map(this::toClaimed).toList(), "");
    }

    @Override
    public MessageFinalityOutcome applyResult(ResultApplyCommand command) {
        var taskId = encode(command.taskId());
        var messageId = encode(command.messageId());
        var activeFrame = commands.hget(activeKey(taskId), messageId);
        if (activeFrame == null) {
            return commands.hexists(finalRowsKey(taskId), messageId)
                    ? MessageFinalityOutcome.duplicateOrLate(
                    command.taskId(), command.messageId(), command.attemptNo(), "already final")
                    : rejected(command, "active lease not found");
        }
        var active = ActiveFrame.decode(activeFrame);
        if (!decode(active.leaseToken()).equals(command.leaseToken())
                || !decode(active.workerId()).equals(command.workerId())
                || active.ready().attemptNo() != command.attemptNo()) {
            return rejected(command, "active lease correlation mismatch");
        }
        var retryAtMillis = command.observedAtMillis() + command.retryPolicy().retryDelayMillis();
        var action = "FINAL";
        var retryFrame = "";
        if (!command.success() && canRetry(command)) {
            var retry = active.ready().nextAttempt(command.runtimeEpoch());
            retryFrame = retry.encode();
            action = command.retryPolicy().retryMode() == RetryMode.DUE_TIME
                    && retryAtMillis > command.observedAtMillis()
                    ? "RETRY_DELAYED"
                    : "RETRY_READY";
        }
        var finalRow = GSON.toJson(new FinalResultRow(
                command.taskId(),
                command.messageId(),
                0L,
                command.attemptNo(),
                command.workerId(),
                decodeNullable(active.batchId()),
                command.source(),
                command.success(),
                command.resultPayloadJson(),
                command.failureReason(),
                command.observedAtMillis(),
                finalExpiresAt(command)));
        String result = commands.eval(APPLY_RESULT_SCRIPT, ScriptOutputType.VALUE, new String[]{
                activeKey(taskId),
                readyKey(taskId),
                delayedKey(taskId),
                finalRowsKey(taskId),
                finalOrderKey(taskId),
                finalSeqKey(taskId),
                dirtyKey(),
                workerActiveKey(active.workerId())
        }, messageId, activeFrame, action, retryFrame, Long.toString(retryAtMillis), finalRow, taskId,
                taskId + "|" + messageId);
        return switch (result) {
            case "FINAL" -> MessageFinalityOutcome.logicalFinal(
                    command.taskId(), command.messageId(), command.attemptNo(), finalExpiresAt(command));
            case "RETRY" -> MessageFinalityOutcome.retryScheduled(
                    command.taskId(), command.messageId(), command.attemptNo(), retryAtMillis, command.failureReason());
            case "DUPLICATE" -> MessageFinalityOutcome.duplicateOrLate(
                    command.taskId(), command.messageId(), command.attemptNo(), "already final");
            default -> rejected(command, result == null ? "result rejected" : result);
        };
    }

    @Override
    public ResultCorrelationSnapshot getResultCorrelation(String taskId, String messageId) {
        var activeFrame = commands.hget(activeKey(encode(taskId)), encode(messageId));
        if (activeFrame == null) {
            return ResultCorrelationSnapshot.missing(taskId, messageId);
        }
        var active = ActiveFrame.decode(activeFrame);
        return new ResultCorrelationSnapshot(
                taskId,
                messageId,
                decode(active.leaseToken()),
                decode(active.workerId()),
                active.ready().attemptNo(),
                true);
    }

    @Override
    public ActiveLeaseRepairBatch pollExpiredActiveLeases(PollActiveLeaseRepairCommand command) {
        var candidates = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var taskId : commands.smembers(tasksKey())) {
            for (var activeFrame : commands.hvals(activeKey(taskId))) {
                if (candidates.size() >= command.limit()) {
                    return new ActiveLeaseRepairBatch(candidates);
                }
                var active = ActiveFrame.decode(activeFrame);
                if (active.leaseExpireAtMillis() <= command.nowMillis()) {
                    candidates.add(toRepairCandidate(active));
                }
            }
        }
        return new ActiveLeaseRepairBatch(candidates);
    }

    @Override
    public ActiveTaskWorkSnapshot getActiveWorkForTask(ActiveTaskWorkQuery query) {
        var taskId = encode(query.taskId());
        var activeItems = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var activeFrame : commands.hvals(activeKey(taskId))) {
            if (activeItems.size() >= query.limit()) {
                break;
            }
            activeItems.add(toRepairCandidate(ActiveFrame.decode(activeFrame)));
        }
        return new ActiveTaskWorkSnapshot(query.taskId(), activeItems);
    }

    @Override
    public ActiveWorkSnapshot getActiveWorkForWorker(ActiveWorkQuery query) {
        var workerId = encode(query.workerId());
        var activeItems = new ArrayList<ActiveLeaseRepairCandidate>();
        for (var member : commands.smembers(workerActiveKey(workerId))) {
            if (activeItems.size() >= query.limit()) {
                break;
            }
            var parts = member.split("\\|", -1);
            if (parts.length != 2) {
                continue;
            }
            var activeFrame = commands.hget(activeKey(parts[0]), parts[1]);
            if (activeFrame != null) {
                activeItems.add(toRepairCandidate(ActiveFrame.decode(activeFrame)));
            }
        }
        return new ActiveWorkSnapshot(query.workerId(), activeItems);
    }

    @Override
    public FinalResultWindow readFinalResults(FinalResultReadRequest request) {
        var taskId = encode(request.taskId());
        purgeExpiredFinalRows(taskId, clock.getAsLong());
        var messageIds = commands.zrangebyscore(
                finalOrderKey(taskId),
                Range.create((double) request.afterSeq() + 1D, Double.POSITIVE_INFINITY),
                Limit.create(0, request.limit())
        );
        var rows = new ArrayList<FinalResultRow>();
        for (var messageId : messageIds) {
            var rowJson = commands.hget(finalRowsKey(taskId), messageId);
            var seq = commands.zscore(finalOrderKey(taskId), messageId);
            if (rowJson != null) {
                var row = GSON.fromJson(rowJson, FinalResultRow.class);
                rows.add(seq == null ? row : row.withSeq(seq.longValue()));
            }
        }
        long nextAfterSeq = rows.isEmpty() ? request.afterSeq() : rows.getLast().seq();
        Long higher = commands.zcount(
                finalOrderKey(taskId),
                Range.create((double) nextAfterSeq + 1D, Double.POSITIVE_INFINITY)
        );
        Long totalVisible = commands.zcard(finalOrderKey(taskId));
        return new FinalResultWindow(
                request.taskId(),
                rows,
                nextAfterSeq,
                higher != null && higher > 0L,
                totalVisible == null ? 0L : totalVisible);
    }

    @Override
    public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
        if (taskId == null || taskId.isBlank() || messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        var encodedTaskId = encode(taskId);
        var encodedMessageId = encode(messageId);
        purgeExpiredFinalRows(encodedTaskId, clock.getAsLong());
        var rowJson = commands.hget(finalRowsKey(encodedTaskId), encodedMessageId);
        if (rowJson == null) {
            return Optional.empty();
        }
        var seq = commands.zscore(finalOrderKey(encodedTaskId), encodedMessageId);
        var row = GSON.fromJson(rowJson, FinalResultRow.class);
        return Optional.of(seq == null ? row : row.withSeq(seq.longValue()));
    }

    @Override
    public TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
        var encodedTaskId = encode(taskId);
        purgeExpiredFinalRows(encodedTaskId, clock.getAsLong());
        long success = 0L;
        long failed = 0L;
        long expired = 0L;
        for (var rowJson : commands.hvals(finalRowsKey(encodedTaskId))) {
            var row = GSON.fromJson(rowJson, FinalResultRow.class);
            if (row.success()) {
                success++;
            } else if (row.source() == ResultApplySource.LEASE_TIMEOUT) {
                expired++;
            } else {
                failed++;
            }
        }
        long ready = commands.llen(readyKey(encodedTaskId));
        long delayed = commands.zcard(delayedKey(encodedTaskId));
        long active = commands.hlen(activeKey(encodedTaskId));
        return new TaskRuntimeProgressSnapshot(
                taskId,
                ready + delayed + active + success + failed + expired,
                ready,
                delayed,
                active,
                success,
                failed,
                expired);
    }

    @Override
    public DiscardTaskRuntimeOutcome discardTaskRuntime(DiscardTaskRuntimeCommand command) {
        var taskId = encode(command.taskId());
        var readyCount = commands.llen(readyKey(taskId)) + commands.zcard(delayedKey(taskId));
        var activeFrames = commands.hvals(activeKey(taskId));
        var finalCount = commands.hlen(finalRowsKey(taskId));
        for (var activeFrame : activeFrames) {
            var active = ActiveFrame.decode(activeFrame);
            commands.srem(workerActiveKey(active.workerId()), taskId + "|" + active.ready().messageId());
        }
        commands.del(
                readyKey(taskId),
                delayedKey(taskId),
                idsKey(taskId),
                activeKey(taskId),
                finalRowsKey(taskId),
                finalOrderKey(taskId),
                finalSeqKey(taskId),
                eligibilityKey(taskId));
        commands.srem(tasksKey(), taskId);
        commands.srem(dirtyKey(), taskId);
        return new DiscardTaskRuntimeOutcome(command.taskId(), readyCount, activeFrames.size(), finalCount);
    }

    @Override
    public DiscardTaskWorkOutcome discardTaskWork(DiscardTaskWorkCommand command) {
        var taskId = encode(command.taskId());
        var readyCount = commands.llen(readyKey(taskId)) + commands.zcard(delayedKey(taskId));
        var activeFrames = commands.hvals(activeKey(taskId));
        for (var activeFrame : activeFrames) {
            var active = ActiveFrame.decode(activeFrame);
            commands.srem(workerActiveKey(active.workerId()), taskId + "|" + active.ready().messageId());
        }
        commands.del(
                readyKey(taskId),
                delayedKey(taskId),
                activeKey(taskId),
                eligibilityKey(taskId));
        commands.srem(tasksKey(), taskId);
        commands.srem(dirtyKey(), taskId);
        return new DiscardTaskWorkOutcome(command.taskId(), readyCount, activeFrames.size());
    }

    @Override
    public void close() {
        connection.close();
    }

    public void shutdown() {
        close();
    }

    private void promoteDue(String taskId, long nowMillis) {
        commands.eval(PROMOTE_DUE_SCRIPT, ScriptOutputType.INTEGER, new String[]{
                delayedKey(taskId),
                readyKey(taskId),
                dirtyKey()
        }, Long.toString(nowMillis), "100", taskId);
    }

    private EligibilityRecord readEligibility(String taskId) {
        var values = commands.hgetall(eligibilityKey(taskId));
        if (values.isEmpty()) {
            return new EligibilityRecord(defaultEligibility(), RuntimeEpoch.of(decode(taskId), 0L));
        }
        var policy = new SchedulerEligibilityPolicy(
                RuntimeGate.valueOf(values.getOrDefault("runtimeGate", RuntimeGate.OPEN.name())),
                values.getOrDefault("dispatchLane", "default"),
                parseLong(values.get("nextEligibleAtMillis")),
                parseLong(values.get("positiveMatchDelayMillis")),
                parseLong(values.get("emptyMatchDelayMillis")),
                parseLong(values.get("contentionRecheckDelayMillis")));
        return new EligibilityRecord(policy, new RuntimeEpoch(
                decode(taskId),
                parseLong(values.get("epoch")),
                decodeNullable(values.get("fence"))));
    }

    private ClaimedWorkItem toClaimed(String activeFrame) {
        var active = ActiveFrame.decode(activeFrame);
        var ready = active.ready();
        return new ClaimedWorkItem(
                decode(ready.taskId()),
                decode(ready.messageId()),
                decodeNullable(ready.eventCode()),
                payload(ready.payloadJson()),
                decodeNullable(ready.payloadRef()),
                decode(active.leaseToken()),
                decode(active.reservationToken()),
                decodeNullableLong(active.scoreBandClaimScore()),
                decode(active.workerId()),
                decode(active.workerGroupId()),
                decodeNullable(active.batchId()),
                ready.attemptNo(),
                active.leaseExpireAtMillis());
    }

    private ActiveLeaseRepairCandidate toRepairCandidate(ActiveFrame active) {
        var ready = active.ready();
        return new ActiveLeaseRepairCandidate(
                decode(ready.taskId()),
                decode(ready.messageId()),
                decode(active.leaseToken()),
                decode(active.workerId()),
                decode(active.workerGroupId()),
                decodeNullable(active.batchId()),
                decode(active.reservationToken()),
                decodeNullableLong(active.scoreBandClaimScore()),
                ready.attemptNo(),
                active.leaseExpireAtMillis());
    }

    private void purgeExpiredFinalRows(String taskId, long nowMillis) {
        for (var messageId : commands.zrange(finalOrderKey(taskId), 0, -1)) {
            var rowJson = commands.hget(finalRowsKey(taskId), messageId);
            if (rowJson == null) {
                commands.zrem(finalOrderKey(taskId), messageId);
                continue;
            }
            var row = GSON.fromJson(rowJson, FinalResultRow.class);
            if (row.expiresAtMillis() > 0 && row.expiresAtMillis() <= nowMillis) {
                commands.hdel(finalRowsKey(taskId), messageId);
                commands.zrem(finalOrderKey(taskId), messageId);
            }
        }
    }

    private boolean canRetry(ResultApplyCommand command) {
        return command.retryPolicy().maxRetryCount() > 0
                && command.attemptNo() <= command.retryPolicy().maxRetryCount();
    }

    private long finalExpiresAt(ResultApplyCommand command) {
        var retentionMillis = command.finalityPolicy().finalResultRetentionMillis();
        return retentionMillis <= 0 ? 0L : command.observedAtMillis() + retentionMillis;
    }

    private MessageFinalityOutcome rejected(ResultApplyCommand command, String reason) {
        return new MessageFinalityOutcome(
                MessageFinalityStatus.REJECTED,
                command.taskId(),
                command.messageId(),
                command.attemptNo(),
                false,
                false,
                0L,
                0L,
                reason);
    }

    private String readyKey(String taskId) {
        return namespace + ":task:" + taskId + ":ready";
    }

    private String delayedKey(String taskId) {
        return namespace + ":task:" + taskId + ":delayed";
    }

    private String idsKey(String taskId) {
        return namespace + ":task:" + taskId + ":ids";
    }

    private String activeKey(String taskId) {
        return namespace + ":task:" + taskId + ":active";
    }

    private String finalRowsKey(String taskId) {
        return namespace + ":task:" + taskId + ":final:rows";
    }

    private String finalOrderKey(String taskId) {
        return namespace + ":task:" + taskId + ":final:order";
    }

    private String finalSeqKey(String taskId) {
        return namespace + ":task:" + taskId + ":final:seq";
    }

    private String eligibilityKey(String taskId) {
        return namespace + ":task:" + taskId + ":eligibility";
    }

    private String workerActiveKey(String workerId) {
        return namespace + ":worker:" + workerId + ":active";
    }

    private String tasksKey() {
        return namespace + ":tasks";
    }

    private String dirtyKey() {
        return namespace + ":dirty";
    }

    private static SchedulerEligibilityPolicy defaultEligibility() {
        return new SchedulerEligibilityPolicy(RuntimeGate.OPEN, "default", 0L, 0L, 0L, 0L);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(requireText(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeNullable(String value) {
        return value == null || value.isBlank()
                ? ""
                : ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeNullableLong(Long value) {
        return value == null ? "" : Long.toString(value);
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String decodeNullable(String value) {
        return value == null || value.isBlank() ? null : decode(value);
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private static Long decodeNullableLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    private static Map<String, Object> payload(String payloadJson) {
        Map<String, Object> payload = GSON.fromJson(decode(payloadJson), MAP_TYPE);
        return payload == null ? Map.of() : payload;
    }

    private record EligibilityRecord(SchedulerEligibilityPolicy policy, RuntimeEpoch runtimeEpoch) {
    }

    private record ReadyFrame(
            String messageId,
            long epoch,
            String fence,
            int attemptNo,
            String payloadJson,
            String taskId,
            String eventCode,
            String payloadRef
    ) {

        private static ReadyFrame initial(
                String messageId,
                String eventCode,
                RuntimeEpoch epoch,
                Map<String, Object> payload,
                String payloadRef
        ) {
            return new ReadyFrame(
                    messageId,
                    epoch.epoch(),
                    encodeNullable(epoch.fenceToken()),
                    1,
                    RedisTaskRuntime.encode(GSON.toJson(payload)),
                    RedisTaskRuntime.encode(epoch.taskId()),
                    encodeNullable(eventCode),
                    encodeNullable(payloadRef));
        }

        private ReadyFrame nextAttempt(RuntimeEpoch nextEpoch) {
            return new ReadyFrame(messageId, nextEpoch.epoch(), encodeNullable(nextEpoch.fenceToken()),
                    attemptNo + 1, payloadJson, RedisTaskRuntime.encode(nextEpoch.taskId()), eventCode, payloadRef);
        }

        private String encode() {
            return String.join("|", messageId, Long.toString(epoch), fence, Integer.toString(attemptNo),
                    payloadJson, taskId, eventCode, payloadRef);
        }

        private static ReadyFrame decode(String value) {
            var parts = value.split("\\|", -1);
            if (parts.length != 8) {
                throw new IllegalArgumentException("invalid ready frame");
            }
            return new ReadyFrame(parts[0], Long.parseLong(parts[1]), parts[2], Integer.parseInt(parts[3]),
                    parts[4], parts[5], parts[6], parts[7]);
        }
    }

    private record ActiveFrame(
            ReadyFrame ready,
            String workerId,
            String workerGroupId,
            String reservationToken,
            String scoreBandClaimScore,
            String dispatchTarget,
            String batchId,
            String leaseToken,
            long leaseExpireAtMillis
    ) {

        private static ActiveFrame decode(String value) {
            var parts = value.split("\\|", -1);
            if (parts.length != 16) {
                throw new IllegalArgumentException("invalid active frame");
            }
            return new ActiveFrame(
                    new ReadyFrame(parts[0], Long.parseLong(parts[1]), parts[2], Integer.parseInt(parts[3]),
                            parts[4], parts[5], parts[6], parts[7]),
                    parts[8],
                    parts[9],
                    parts[10],
                    parts[11],
                    parts[12],
                    parts[13],
                    parts[14],
                    Long.parseLong(parts[15]));
        }
    }
}
