package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.RecentFinalWorkReceipt;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.RuntimeResultApplyContext;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkFinalStatus;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Redis-backed {@link TaskWorkRuntime} implementation for local and embedded
 * runtime use.
 *
 * <p>The hot-path mutating operations are script-backed so queue, delayed,
 * lease, and counter ownership live in Redis as one runtime truth instead of
 * depending on a process-local coarse lock.</p>
 */
public final class RedisTaskWorkRuntime implements TaskWorkRuntime {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    private static final int DEFAULT_MAX_RECENT_FINAL_RECEIPTS = 10_000;
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final char EVENT_SCOPE_SEPARATOR = '\u001F';

    private static final String ENQUEUE_SCRIPT = String.join("\n",
            "local taskId = ARGV[1]",
            "local messageId = ARGV[2]",
            "local eventCode = ARGV[3]",
            "local payloadJson = ARGV[4]",
            "local payloadRef = ARGV[5]",
            "local retryCount = ARGV[6]",
            "local maxRetryCount = ARGV[7]",
            "local shardKey = ARGV[8]",
            "local nextVisibleAtMillis = tonumber(ARGV[9])",
            "local createdAtMillis = tonumber(ARGV[10])",
            "local nowMillis = tonumber(ARGV[11])",
            "local maxReadyPerTask = tonumber(ARGV[12])",
            "local maxQueuedItems = tonumber(ARGV[13])",
            "local workMember = ARGV[14]",
            "if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then",
            "  return 'DUPLICATE'",
            "end",
            "local taskReady = tonumber(redis.call('HGET', KEYS[5], 'readyCount') or '0')",
            "if taskReady >= maxReadyPerTask then",
            "  redis.call('HINCRBY', KEYS[6], 'backpressureRejectedItems', 1)",
            "  return 'BACKPRESSURE_TASK'",
            "end",
            "local globalReady = tonumber(redis.call('HGET', KEYS[6], 'readyCount') or '0')",
            "local globalDelayed = tonumber(redis.call('HGET', KEYS[6], 'delayedCount') or '0')",
            "if globalReady + globalDelayed >= maxQueuedItems then",
            "  redis.call('HINCRBY', KEYS[6], 'backpressureRejectedItems', 1)",
            "  return 'BACKPRESSURE_GLOBAL'",
            "end",
            "redis.call('DEL', KEYS[11])",
            "redis.call('SREM', KEYS[12], messageId)",
            "redis.call('ZREM', KEYS[13], workMember)",
            "redis.call('HSET', KEYS[1],",
            "  'eventCode', eventCode,",
            "  'payloadJson', payloadJson,",
            "  'payloadRef', payloadRef,",
            "  'retryCount', retryCount,",
            "  'maxRetryCount', maxRetryCount,",
            "  'shardKey', shardKey,",
            "  'nextVisibleAtMillis', nextVisibleAtMillis > 0 and tostring(nextVisibleAtMillis) or '',",
            "  'createdAtMillis', tostring(createdAtMillis))",
            "redis.call('SADD', KEYS[3], taskId)",
            "redis.call('SADD', KEYS[4], messageId)",
            "redis.call('HINCRBY', KEYS[5], 'totalCount', 1)",
            "redis.call('HINCRBY', KEYS[6], 'enqueuedItems', 1)",
            "if nextVisibleAtMillis > nowMillis then",
            "  redis.call('ZADD', KEYS[7], nextVisibleAtMillis, workMember)",
            "  redis.call('ZADD', KEYS[8], nextVisibleAtMillis, messageId)",
            "  redis.call('HINCRBY', KEYS[5], 'delayedCount', 1)",
            "  redis.call('HINCRBY', KEYS[6], 'delayedCount', 1)",
            "else",
            "  redis.call('RPUSH', KEYS[9], messageId)",
            "  local existingReadyScore = redis.call('ZSCORE', KEYS[10], taskId)",
            "  if (not existingReadyScore) or tonumber(existingReadyScore) > createdAtMillis then",
            "    redis.call('ZADD', KEYS[10], createdAtMillis, taskId)",
            "  end",
            "  redis.call('HINCRBY', KEYS[5], 'readyCount', 1)",
            "  redis.call('HINCRBY', KEYS[6], 'readyCount', 1)",
            "end",
            "return 'ENQUEUED'"
    );

    private static final String CLAIM_READY_SCRIPT = String.join("\n",
            "local readyQueue = KEYS[1]",
            "local taskDelayed = KEYS[2]",
            "local readyTasks = KEYS[3]",
            "local delayedWork = KEYS[4]",
            "local taskActive = KEYS[5]",
            "local leaseExpiry = KEYS[6]",
            "local taskStats = KEYS[7]",
            "local runtimeStats = KEYS[8]",
            "local taskId = ARGV[1]",
            "local taskPrefix = ARGV[2]",
            "local namespace = ARGV[3]",
            "local nowMillis = tonumber(ARGV[4])",
            "local leaseExpireAtMillis = tonumber(ARGV[5])",
            "local dueCount = tonumber(ARGV[6])",
            "local slotCount = tonumber(ARGV[7])",
            "local function event_scope_matches(encodedScope, eventCode)",
            "  if not encodedScope or encodedScope == '' then",
            "    return true",
            "  end",
            "  if not eventCode or eventCode == '' then",
            "    return false",
            "  end",
            "  return string.find(encodedScope, string.char(31) .. eventCode .. string.char(31), 1, true) ~= nil",
            "end",
            "local function work_member(messageId)",
            "  return tostring(string.len(taskId)) .. ':' .. taskId .. messageId",
            "end",
            "local function worker_active(workerId)",
            "  return namespace .. ':worker:' .. workerId .. ':active'",
            "end",
            "local function decr_non_negative(hashKey, field, delta)",
            "  local current = tonumber(redis.call('HGET', hashKey, field) or '0')",
            "  local next = current - delta",
            "  if next < 0 then next = 0 end",
            "  redis.call('HSET', hashKey, field, tostring(next))",
            "end",
            "local function maybe_upsert_ready_score()",
            "  local headMessageId = redis.call('LINDEX', readyQueue, 0)",
            "  if not headMessageId then",
            "    redis.call('ZREM', readyTasks, taskId)",
            "    return",
            "  end",
            "  local headCreatedAt = tonumber(redis.call('HGET', taskPrefix .. ':work:' .. headMessageId, 'createdAtMillis') or '0')",
            "  local existing = redis.call('ZSCORE', readyTasks, taskId)",
            "  if (not existing) or tonumber(existing) > headCreatedAt then",
            "    redis.call('ZADD', readyTasks, headCreatedAt, taskId)",
            "  end",
            "end",
            "local argCursor = 8",
            "for i = 1, dueCount do",
            "  local messageId = ARGV[argCursor]",
            "  local member = ARGV[argCursor + 1]",
            "  argCursor = argCursor + 2",
            "  redis.call('ZREM', taskDelayed, messageId)",
            "  redis.call('ZREM', delayedWork, member)",
            "  local workHash = taskPrefix .. ':work:' .. messageId",
            "  local leaseHash = taskPrefix .. ':lease:' .. messageId",
            "  if redis.call('EXISTS', workHash) == 1 and redis.call('EXISTS', leaseHash) == 0 then",
            "    local nextVisibleAtMillis = tonumber(redis.call('HGET', workHash, 'nextVisibleAtMillis') or '0')",
            "    if nextVisibleAtMillis > nowMillis then",
            "      redis.call('ZADD', taskDelayed, nextVisibleAtMillis, messageId)",
            "      redis.call('ZADD', delayedWork, nextVisibleAtMillis, member)",
            "    else",
            "      redis.call('RPUSH', readyQueue, messageId)",
            "      decr_non_negative(taskStats, 'delayedCount', 1)",
            "      decr_non_negative(runtimeStats, 'delayedCount', 1)",
            "      redis.call('HINCRBY', taskStats, 'readyCount', 1)",
            "      redis.call('HINCRBY', runtimeStats, 'readyCount', 1)",
            "      local createdAtMillis = tonumber(redis.call('HGET', workHash, 'createdAtMillis') or '0')",
            "      local existing = redis.call('ZSCORE', readyTasks, taskId)",
            "      if (not existing) or tonumber(existing) > createdAtMillis then",
            "        redis.call('ZADD', readyTasks, createdAtMillis, taskId)",
            "      end",
            "    end",
            "  end",
            "end",
            "local claimed = {}",
            "for slot = 1, slotCount do",
            "  local workerBase = argCursor + ((slot - 1) * 4)",
            "  local workerId = ARGV[workerBase]",
            "  local batchId = ARGV[workerBase + 1]",
            "  local leaseToken = ARGV[workerBase + 2]",
            "  local encodedScope = ARGV[workerBase + 3]",
            "  local queueLength = redis.call('LLEN', readyQueue)",
            "  local scanned = 0",
            "  local messageId = redis.call('LPOP', readyQueue)",
            "  while messageId do",
            "    local workHash = taskPrefix .. ':work:' .. messageId",
            "    local leaseHash = taskPrefix .. ':lease:' .. messageId",
            "    if redis.call('EXISTS', workHash) == 1 and redis.call('EXISTS', leaseHash) == 0 then",
            "      local retryCount = tonumber(redis.call('HGET', workHash, 'retryCount') or '0')",
            "      local eventCode = redis.call('HGET', workHash, 'eventCode') or ''",
            "      if event_scope_matches(encodedScope, eventCode) then",
            "        decr_non_negative(taskStats, 'readyCount', 1)",
            "        decr_non_negative(runtimeStats, 'readyCount', 1)",
            "        local payloadJson = redis.call('HGET', workHash, 'payloadJson') or ''",
            "        local payloadRef = redis.call('HGET', workHash, 'payloadRef') or ''",
            "        local workMember = work_member(messageId)",
            "        redis.call('HSET', leaseHash,",
            "          'leaseToken', leaseToken,",
            "          'workerId', workerId,",
            "          'batchId', batchId,",
            "          'payloadRef', payloadRef,",
            "          'retryCount', tostring(retryCount),",
            "          'leaseExpireAtMillis', tostring(leaseExpireAtMillis),",
            "          'leasedAtMillis', tostring(nowMillis))",
            "        redis.call('SADD', taskActive, workMember)",
            "        redis.call('SADD', worker_active(workerId), workMember)",
            "        redis.call('ZADD', leaseExpiry, leaseExpireAtMillis, workMember)",
            "        redis.call('HINCRBY', taskStats, 'inflightCount', 1)",
            "        redis.call('HINCRBY', runtimeStats, 'inflightCount', 1)",
            "        redis.call('HINCRBY', runtimeStats, 'claimedItems', 1)",
            "        table.insert(claimed, messageId)",
            "        table.insert(claimed, leaseToken)",
            "        table.insert(claimed, workerId)",
            "        table.insert(claimed, batchId)",
            "        table.insert(claimed, eventCode)",
            "        table.insert(claimed, payloadJson)",
            "        table.insert(claimed, payloadRef)",
            "        table.insert(claimed, tostring(retryCount))",
            "        break",
            "      end",
            "      redis.call('RPUSH', readyQueue, messageId)",
            "    else",
            "      decr_non_negative(taskStats, 'readyCount', 1)",
            "      decr_non_negative(runtimeStats, 'readyCount', 1)",
            "    end",
            "    scanned = scanned + 1",
            "    if scanned >= queueLength then",
            "      messageId = nil",
            "      break",
            "    end",
            "    messageId = redis.call('LPOP', readyQueue)",
            "  end",
            "end",
            "maybe_upsert_ready_score()",
            "return claimed"
    );

    private static final String APPLY_RESULT_SCRIPT = String.join("\n",
            "local taskId = ARGV[1]",
            "local messageId = ARGV[2]",
            "local workMember = ARGV[3]",
            "local providedLeaseToken = ARGV[4]",
            "local nowMillis = tonumber(ARGV[5])",
            "local completedAtMillis = tonumber(ARGV[6])",
            "local success = ARGV[7] == '1'",
            "local expired = ARGV[8] == '1'",
            "local retryable = ARGV[9] == '1'",
            "local retryVisibleAtMillis = tonumber(ARGV[10])",
            "local errorCode = ARGV[11]",
            "local finalStatus = ARGV[12]",
            "local taskPrefix = ARGV[13]",
            "local function decr_non_negative(hashKey, field, delta)",
            "  local current = tonumber(redis.call('HGET', hashKey, field) or '0')",
            "  local next = current - delta",
            "  if next < 0 then next = 0 end",
            "  redis.call('HSET', hashKey, field, tostring(next))",
            "end",
            "local leaseToken = redis.call('HGET', KEYS[2], 'leaseToken')",
            "if not leaseToken or leaseToken == '' then",
            "  redis.call('HINCRBY', KEYS[8], 'duplicateResultItems', 1)",
            "  return {'NO_ACTIVE_LEASE'}",
            "end",
            "if providedLeaseToken ~= '' and leaseToken ~= providedLeaseToken then",
            "  redis.call('HINCRBY', KEYS[8], 'staleResultItems', 1)",
            "  return {'STALE_LEASE'}",
            "end",
            "local workerId = redis.call('HGET', KEYS[2], 'workerId') or ''",
            "local retryCount = tonumber(redis.call('HGET', KEYS[2], 'retryCount') or '0')",
            "redis.call('DEL', KEYS[2])",
            "redis.call('SREM', KEYS[4], workMember)",
            "if workerId ~= '' then",
            "  redis.call('SREM', KEYS[3] .. workerId .. ':active', workMember)",
            "end",
            "redis.call('ZREM', KEYS[5], workMember)",
            "decr_non_negative(KEYS[7], 'inflightCount', 1)",
            "decr_non_negative(KEYS[8], 'inflightCount', 1)",
            "redis.call('HINCRBY', KEYS[8], 'resultAppliedItems', 1)",
            "if success then",
            "  redis.call('DEL', KEYS[1])",
            "  redis.call('SREM', KEYS[6], messageId)",
            "  redis.call('HINCRBY', KEYS[7], 'successCount', 1)",
            "  redis.call('HSET', KEYS[9], 'status', finalStatus, 'errorCode', errorCode, 'retryCount', tostring(retryCount), 'completedAtMillis', tostring(completedAtMillis))",
            "  redis.call('SADD', KEYS[10], messageId)",
            "  redis.call('ZADD', KEYS[11], completedAtMillis, workMember)",
            "  return {'SUCCESS_APPLIED', tostring(retryCount)}",
            "end",
            "local itemRetryCount = tonumber(redis.call('HGET', KEYS[1], 'retryCount') or '-1')",
            "local itemMaxRetryCount = tonumber(redis.call('HGET', KEYS[1], 'maxRetryCount') or '-1')",
            "if retryable and itemRetryCount >= 0 and itemRetryCount < itemMaxRetryCount then",
            "  local nextRetryCount = itemRetryCount + 1",
            "  redis.call('HSET', KEYS[1], 'retryCount', tostring(nextRetryCount), 'nextVisibleAtMillis', retryVisibleAtMillis > nowMillis and tostring(retryVisibleAtMillis) or '')",
            "  if retryVisibleAtMillis > nowMillis then",
            "    redis.call('ZADD', KEYS[12], retryVisibleAtMillis, workMember)",
            "    redis.call('ZADD', KEYS[13], retryVisibleAtMillis, messageId)",
            "    redis.call('HINCRBY', KEYS[7], 'delayedCount', 1)",
            "    redis.call('HINCRBY', KEYS[8], 'delayedCount', 1)",
            "  else",
            "    redis.call('RPUSH', KEYS[14], messageId)",
            "    local createdAtMillis = tonumber(redis.call('HGET', KEYS[1], 'createdAtMillis') or '0')",
            "    local existing = redis.call('ZSCORE', KEYS[15], taskId)",
            "    if (not existing) or tonumber(existing) > createdAtMillis then",
            "      redis.call('ZADD', KEYS[15], createdAtMillis, taskId)",
            "    end",
            "    redis.call('HINCRBY', KEYS[7], 'readyCount', 1)",
            "    redis.call('HINCRBY', KEYS[8], 'readyCount', 1)",
            "  end",
            "  return {'RETRY_SCHEDULED', tostring(nextRetryCount)}",
            "end",
            "redis.call('DEL', KEYS[1])",
            "redis.call('SREM', KEYS[6], messageId)",
            "if expired then",
            "  redis.call('HINCRBY', KEYS[7], 'expiredCount', 1)",
            "else",
            "  redis.call('HINCRBY', KEYS[7], 'failedCount', 1)",
            "end",
            "redis.call('HSET', KEYS[9], 'status', finalStatus, 'errorCode', errorCode, 'retryCount', tostring(retryCount), 'completedAtMillis', tostring(completedAtMillis))",
            "redis.call('SADD', KEYS[10], messageId)",
            "redis.call('ZADD', KEYS[11], completedAtMillis, workMember)",
            "return {'FAILURE_FINALIZED', tostring(retryCount)}"
    );

    /**
     * Atomic variant of {@link #APPLY_RESULT_SCRIPT} that captures the full
     * pre-apply lease and work snapshot before deleting the lease hash.
     *
     * <p>Return layout (all as bulk strings inside a Redis multi-bulk reply):
     * <ol>
     *   <li>{@code "NO_ACTIVE_LEASE"} 鈥?1 element; no lease was active (duplicate
     *       / late callback). No further elements.</li>
     *   <li>All other outcomes 鈥?8 elements:
     *     <ol>
     *       <li>status: {@code SUCCESS_APPLIED} / {@code RETRY_SCHEDULED} /
     *           {@code FAILURE_FINALIZED} / {@code STALE_LEASE}</li>
     *       <li>workerId</li>
     *       <li>batchId</li>
     *       <li>activeLeaseToken (captured before delete)</li>
     *       <li>payloadRef</li>
     *       <li>retryCount (from lease at apply time)</li>
     *       <li>maxRetryCount (from work hash)</li>
     *       <li>leasedAtMillis</li>
     *     </ol>
     *   </li>
     * </ol>
     * The same KEYS and ARGV layout as {@link #APPLY_RESULT_SCRIPT} is used so
     * both scripts can share the same call-site key/arg construction.</p>
     */
    private static final String APPLY_RESULT_WITH_CONTEXT_SCRIPT = String.join("\n",
            "local taskId = ARGV[1]",
            "local messageId = ARGV[2]",
            "local workMember = ARGV[3]",
            "local providedLeaseToken = ARGV[4]",
            "local nowMillis = tonumber(ARGV[5])",
            "local completedAtMillis = tonumber(ARGV[6])",
            "local success = ARGV[7] == '1'",
            "local expired = ARGV[8] == '1'",
            "local retryable = ARGV[9] == '1'",
            "local retryVisibleAtMillis = tonumber(ARGV[10])",
            "local errorCode = ARGV[11]",
            "local finalStatus = ARGV[12]",
            "local taskPrefix = ARGV[13]",
            "local function decr_non_negative(hashKey, field, delta)",
            "  local current = tonumber(redis.call('HGET', hashKey, field) or '0')",
            "  local next = current - delta",
            "  if next < 0 then next = 0 end",
            "  redis.call('HSET', hashKey, field, tostring(next))",
            "end",
            "local leaseToken = redis.call('HGET', KEYS[2], 'leaseToken')",
            "if not leaseToken or leaseToken == '' then",
            "  redis.call('HINCRBY', KEYS[8], 'duplicateResultItems', 1)",
            "  return {'NO_ACTIVE_LEASE'}",
            "end",
            "-- Capture full lease snapshot (before any mutation, before potential DEL)",
            "local workerId = redis.call('HGET', KEYS[2], 'workerId') or ''",
            "local batchId = redis.call('HGET', KEYS[2], 'batchId') or ''",
            "local payloadRef = redis.call('HGET', KEYS[2], 'payloadRef') or ''",
            "local retryCount = tonumber(redis.call('HGET', KEYS[2], 'retryCount') or '0')",
            "local leasedAtMillis = redis.call('HGET', KEYS[2], 'leasedAtMillis') or '0'",
            "local maxRetryCount = tonumber(redis.call('HGET', KEYS[1], 'maxRetryCount') or '0')",
            "local snapshot = {workerId, batchId, leaseToken, payloadRef,",
            "                   tostring(retryCount), tostring(maxRetryCount), leasedAtMillis}",
            "if providedLeaseToken ~= '' and leaseToken ~= providedLeaseToken then",
            "  redis.call('HINCRBY', KEYS[8], 'staleResultItems', 1)",
            "  local r = {'STALE_LEASE'}",
            "  for _, v in ipairs(snapshot) do table.insert(r, v) end",
            "  return r",
            "end",
            "redis.call('DEL', KEYS[2])",
            "redis.call('SREM', KEYS[4], workMember)",
            "if workerId ~= '' then",
            "  redis.call('SREM', KEYS[3] .. workerId .. ':active', workMember)",
            "end",
            "redis.call('ZREM', KEYS[5], workMember)",
            "decr_non_negative(KEYS[7], 'inflightCount', 1)",
            "decr_non_negative(KEYS[8], 'inflightCount', 1)",
            "redis.call('HINCRBY', KEYS[8], 'resultAppliedItems', 1)",
            "if success then",
            "  redis.call('DEL', KEYS[1])",
            "  redis.call('SREM', KEYS[6], messageId)",
            "  redis.call('HINCRBY', KEYS[7], 'successCount', 1)",
            "  redis.call('HSET', KEYS[9], 'status', finalStatus, 'errorCode', errorCode, 'retryCount', tostring(retryCount), 'completedAtMillis', tostring(completedAtMillis))",
            "  redis.call('SADD', KEYS[10], messageId)",
            "  redis.call('ZADD', KEYS[11], completedAtMillis, workMember)",
            "  local r = {'SUCCESS_APPLIED'}",
            "  for _, v in ipairs(snapshot) do table.insert(r, v) end",
            "  return r",
            "end",
            "local itemRetryCount = tonumber(redis.call('HGET', KEYS[1], 'retryCount') or '-1')",
            "local itemMaxRetryCount = tonumber(redis.call('HGET', KEYS[1], 'maxRetryCount') or '-1')",
            "if retryable and itemRetryCount >= 0 and itemRetryCount < itemMaxRetryCount then",
            "  local nextRetryCount = itemRetryCount + 1",
            "  redis.call('HSET', KEYS[1], 'retryCount', tostring(nextRetryCount), 'nextVisibleAtMillis', retryVisibleAtMillis > nowMillis and tostring(retryVisibleAtMillis) or '')",
            "  if retryVisibleAtMillis > nowMillis then",
            "    redis.call('ZADD', KEYS[12], retryVisibleAtMillis, workMember)",
            "    redis.call('ZADD', KEYS[13], retryVisibleAtMillis, messageId)",
            "    redis.call('HINCRBY', KEYS[7], 'delayedCount', 1)",
            "    redis.call('HINCRBY', KEYS[8], 'delayedCount', 1)",
            "  else",
            "    redis.call('RPUSH', KEYS[14], messageId)",
            "    local createdAtMillis = tonumber(redis.call('HGET', KEYS[1], 'createdAtMillis') or '0')",
            "    local existing = redis.call('ZSCORE', KEYS[15], taskId)",
            "    if (not existing) or tonumber(existing) > createdAtMillis then",
            "      redis.call('ZADD', KEYS[15], createdAtMillis, taskId)",
            "    end",
            "    redis.call('HINCRBY', KEYS[7], 'readyCount', 1)",
            "    redis.call('HINCRBY', KEYS[8], 'readyCount', 1)",
            "  end",
            "  local r = {'RETRY_SCHEDULED'}",
            "  for _, v in ipairs(snapshot) do table.insert(r, v) end",
            "  return r",
            "end",
            "redis.call('DEL', KEYS[1])",
            "redis.call('SREM', KEYS[6], messageId)",
            "if expired then",
            "  redis.call('HINCRBY', KEYS[7], 'expiredCount', 1)",
            "else",
            "  redis.call('HINCRBY', KEYS[7], 'failedCount', 1)",
            "end",
            "redis.call('HSET', KEYS[9], 'status', finalStatus, 'errorCode', errorCode, 'retryCount', tostring(retryCount), 'completedAtMillis', tostring(completedAtMillis))",
            "redis.call('SADD', KEYS[10], messageId)",
            "redis.call('ZADD', KEYS[11], completedAtMillis, workMember)",
            "local r = {'FAILURE_FINALIZED'}",
            "for _, v in ipairs(snapshot) do table.insert(r, v) end",
            "return r"
    );

    private static final String POLL_EXPIRED_LEASES_SCRIPT = String.join("\n",
            "local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])",
            "for _, member in ipairs(due) do",
            "  redis.call('ZREM', KEYS[1], member)",
            "end",
            "return due"
    );

    private static final String DISCARD_TASK_SCRIPT = String.join("\n",
            "local taskId = ARGV[1]",
            "local taskPrefix = ARGV[2]",
            "local namespace = ARGV[3]",
            "local memberCount = tonumber(ARGV[4])",
            "local recentFinalCount = tonumber(ARGV[5])",
            "local function decr_non_negative(hashKey, field, delta)",
            "  local current = tonumber(redis.call('HGET', hashKey, field) or '0')",
            "  local next = current - delta",
            "  if next < 0 then next = 0 end",
            "  redis.call('HSET', hashKey, field, tostring(next))",
            "end",
            "local activeMembers = redis.call('SMEMBERS', KEYS[2])",
            "for _, member in ipairs(activeMembers) do",
            "  local workerId = ''",
            "  local leaseHash = taskPrefix .. ':lease:'",
            "  local separator = string.find(member, ':')",
            "  if separator then",
            "    local taskLen = tonumber(string.sub(member, 1, separator - 1))",
            "    local messageId = string.sub(member, separator + taskLen + 1)",
            "    leaseHash = leaseHash .. messageId",
            "    workerId = redis.call('HGET', leaseHash, 'workerId') or ''",
            "  end",
            "  redis.call('DEL', leaseHash)",
            "  if workerId ~= '' then",
            "    redis.call('SREM', namespace .. ':worker:' .. workerId .. ':active', member)",
            "  end",
            "  redis.call('ZREM', KEYS[3], member)",
            "end",
            "local discarded = 0",
            "local cursor = 6",
            "for i = 1, memberCount do",
            "  local messageId = ARGV[cursor]",
            "  local workMember = ARGV[cursor + 1]",
            "  cursor = cursor + 2",
            "  local workHash = taskPrefix .. ':work:' .. messageId",
            "  if redis.call('EXISTS', workHash) == 1 then",
            "    discarded = discarded + 1",
            "  end",
            "  redis.call('DEL', workHash, taskPrefix .. ':lease:' .. messageId)",
            "  redis.call('ZREM', KEYS[4], workMember)",
            "  redis.call('ZREM', KEYS[5], messageId)",
            "end",
            "for i = 1, recentFinalCount do",
            "  local messageId = ARGV[cursor]",
            "  cursor = cursor + 1",
            "  redis.call('DEL', taskPrefix .. ':recent-final:' .. messageId)",
            "end",
            "local readyCount = tonumber(redis.call('HGET', KEYS[6], 'readyCount') or '0')",
            "local delayedCount = tonumber(redis.call('HGET', KEYS[6], 'delayedCount') or '0')",
            "local inflightCount = tonumber(redis.call('HGET', KEYS[6], 'inflightCount') or '0')",
            "redis.call('DEL', KEYS[1], KEYS[2], KEYS[5], KEYS[6], KEYS[7], KEYS[8])",
            "redis.call('ZREM', KEYS[9], taskId)",
            "redis.call('SREM', KEYS[10], taskId)",
            "decr_non_negative(KEYS[11], 'readyCount', readyCount)",
            "decr_non_negative(KEYS[11], 'delayedCount', delayedCount)",
            "decr_non_negative(KEYS[11], 'inflightCount', inflightCount)",
            "if discarded > 0 then",
            "  redis.call('HINCRBY', KEYS[11], 'discardedItems', discarded)",
            "end",
            "return tostring(discarded)"
    );

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisTaskWorkKeyspace keyspace;
    private final Supplier<Instant> clock;
    private final int maxQueuedItems;
    private final int maxRecentFinalReceipts;
    private final boolean ownsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisTaskWorkRuntime(String redisUri) {
        this(redisUri, RedisTaskWorkKeyspace.DEFAULT_NAMESPACE, DEFAULT_MAX_QUEUED_ITEMS);
    }

    public RedisTaskWorkRuntime(String redisUri, String namespace, int maxQueuedItems) {
        this(RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespace,
                maxQueuedItems,
                Instant::now,
                true);
    }

    RedisTaskWorkRuntime(RedisClient redisClient,
                         String namespace,
                         int maxQueuedItems,
                         Supplier<Instant> clock,
                         boolean ownsClient) {
        this(redisClient,
                Objects.requireNonNull(redisClient, "redisClient").connect(),
                new RedisTaskWorkKeyspace(namespace),
                maxQueuedItems,
                clock,
                ownsClient);
    }

    RedisTaskWorkRuntime(StatefulRedisConnection<String, String> connection,
                         RedisTaskWorkKeyspace keyspace,
                         int maxQueuedItems,
                         Supplier<Instant> clock) {
        this(null, connection, keyspace, maxQueuedItems, clock, false);
    }

    private RedisTaskWorkRuntime(RedisClient redisClient,
                                 StatefulRedisConnection<String, String> connection,
                                 RedisTaskWorkKeyspace keyspace,
                                 int maxQueuedItems,
                                 Supplier<Instant> clock,
                                 boolean ownsClient) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        this.redisClient = redisClient;
        this.connection = Objects.requireNonNull(connection, "connection");
        this.commands = connection.sync();
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxQueuedItems = maxQueuedItems;
        this.maxRecentFinalReceipts = Integer.getInteger(
                "xa.mass.runtime.recentFinalReceiptMaxEntries",
                DEFAULT_MAX_RECENT_FINAL_RECEIPTS
        );
        this.ownsClient = ownsClient;
    }

    @Override
    public WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options) {
        if (!running.get()) {
            return WorkEnqueueOutcome.unavailable(item, "work runtime is stopped");
        }
        if (item == null || isBlank(item.taskId()) || isBlank(item.messageId())) {
            return WorkEnqueueOutcome.invalid(item, "taskId and messageId must not be blank");
        }
        try {
            WorkEnqueueStatus status = enqueueAtomic(item, options == null ? WorkEnqueueOptions.DEFAULT : options);
            return switch (status) {
                case ENQUEUED -> WorkEnqueueOutcome.enqueued(item);
                case DUPLICATE -> WorkEnqueueOutcome.duplicate(item, "work item already exists");
                case BACKPRESSURE_REJECTED -> WorkEnqueueOutcome.backpressureRejected(item, "task or runtime backlog is full");
                default -> WorkEnqueueOutcome.failed(item, "unexpected enqueue status");
            };
        } catch (RuntimeException ex) {
            return WorkEnqueueOutcome.unavailable(item, "redis runtime is unavailable: " + ex.getMessage());
        }
    }

    @Override
    public List<String> readyTaskIds(int limit) {
        if (limit <= 0 || !running.get()) {
            return List.of();
        }
        try {
            promoteDueDelayedLocked(clock.get(), limit);
            return loadReadyTaskIds(limit);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public List<ClaimedTaskWork> claimReady(String taskId,
                                            List<WorkerClaimTarget> workers,
                                            TaskWorkClaimOptions options) {
        if (!running.get() || isBlank(taskId) || workers == null || workers.isEmpty() || options == null) {
            return List.of();
        }
        if (options.maxItems() <= 0) {
            return List.of();
        }
        try {
            return claimReadyAtomic(taskId, workers, options);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public ResultApplyOutcome applyResult(TaskWorkResult result) {
        if (!running.get()) {
            return ResultApplyOutcome.failed(result, "work runtime is stopped");
        }
        if (result == null || isBlank(result.taskId()) || isBlank(result.messageId())) {
            return ResultApplyOutcome.invalid(result, "taskId and messageId must not be blank");
        }
        try {
            return applyResultAtomic(result);
        } catch (RuntimeException ex) {
            return ResultApplyOutcome.failed(result, "redis runtime is unavailable: " + ex.getMessage());
        }
    }

    /**
     * Atomically applies a work result and returns the pre-apply lease/work
     * snapshot using a single Lua script execution 鈥?no separate round-trips for
     * lease or work reads on the hot callback path.
     */
    @Override
    public RuntimeResultApplyContext applyResultWithContext(TaskWorkResult result) {
        if (!running.get()) {
            return RuntimeResultApplyContext.noLease(
                    ResultApplyOutcome.failed(result, "work runtime is stopped"));
        }
        if (result == null || isBlank(result.taskId()) || isBlank(result.messageId())) {
            return RuntimeResultApplyContext.noLease(
                    ResultApplyOutcome.invalid(result, "taskId and messageId must not be blank"));
        }
        try {
            return applyResultWithContextAtomic(result);
        } catch (RuntimeException ex) {
            return RuntimeResultApplyContext.noLease(
                    ResultApplyOutcome.failed(result, "redis runtime is unavailable: " + ex.getMessage()));
        }
    }

    @Override
    public List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        try {
            return pollExpiredLeasesAtomic(limit, now == null ? clock.get() : now);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public List<ActiveLeaseRecord> activeLeases(String taskId) {
        if (!running.get() || isBlank(taskId)) {
            return List.of();
        }
        try {
            List<ActiveLeaseRecord> leases = new ArrayList<>();
            for (String member : commands.smembers(keyspace.taskActiveSet(taskId))) {
                RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
                ActiveLeaseRecord lease = loadLease(ref.taskId(), ref.messageId());
                if (lease != null) {
                    leases.add(lease);
                }
            }
            return List.copyOf(leases);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    @Override
    public Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(loadLease(taskId, messageId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<TaskWorkEnvelope> getWork(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(loadWork(taskId, messageId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RecentFinalWorkReceipt> getRecentFinalReceipt(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(loadRecentFinalReceipt(taskId, messageId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public boolean hasReadyWork(String taskId) {
        if (isBlank(taskId) || !running.get()) {
            return false;
        }
        try {
            promoteDueDelayedForTaskLocked(taskId, clock.get());
            return ensureReadyQueueVisible(taskId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public boolean hasActiveLeaseForWorker(String taskId, String workerId) {
        if (isBlank(taskId) || isBlank(workerId) || !running.get()) {
            return false;
        }
        try {
            for (String member : commands.smembers(keyspace.workerActiveSet(workerId))) {
                RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
                if (taskId.equals(ref.taskId()) && loadLease(ref.taskId(), ref.messageId()) != null) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public TaskWorkStats stats(String taskId) {
        if (!running.get() || isBlank(taskId)) {
            return TaskWorkStats.EMPTY;
        }
        try {
            promoteDueDelayedForTaskLocked(taskId, clock.get());
            return loadTaskStats(taskId);
        } catch (RuntimeException ex) {
            return TaskWorkStats.EMPTY;
        }
    }

    @Override
    public TaskWorkRuntimeStats stats() {
        if (!running.get()) {
            return emptyRuntimeStats();
        }
        try {
            promoteDueDelayedLocked(clock.get(), 256);
            Map<String, String> runtimeStats = commands.hgetall(keyspace.runtimeStatsHash());
            return new TaskWorkRuntimeStats(
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_READY_COUNT)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT)),
                    Math.toIntExact(commands.zcard(keyspace.readyTasksZset())),
                    maxQueuedItems,
                    oldestReadyAgeMillis(),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_ENQUEUED_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_CLAIMED_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_RESULT_APPLIED_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_BACKPRESSURE_REJECTED_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_DUPLICATE_RESULT_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_STALE_RESULT_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_EXPIRED_LEASE_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_DISCARDED_ITEMS)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_SHUTDOWN_CLEARED_ITEMS))
            );
        } catch (RuntimeException ex) {
            return emptyRuntimeStats();
        }
    }

    @Override
    public long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        try {
            return discardTaskAtomic(taskId);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            closeRedisResources();
            return;
        }
        try {
            try {
                long cleared = 0L;
                for (String taskId : commands.smembers(keyspace.taskRegistrySet())) {
                    cleared += discardTaskAtomic(taskId);
                }
                if (cleared > 0) {
                    incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_SHUTDOWN_CLEARED_ITEMS, cleared);
                }
                commands.del(
                        keyspace.readyTasksZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.leaseExpiryZset(),
                        keyspace.recentFinalReceiptsZset(),
                        keyspace.taskRegistrySet()
                );
            } catch (RuntimeException ignored) {
                // Redis is already unavailable; local shutdown still needs to release resources.
            }
        } finally {
            closeRedisResources();
        }
    }

    private WorkEnqueueStatus enqueueAtomic(TaskWorkEnvelope item, WorkEnqueueOptions options) {
        Instant now = clock.get();
        Object outcome = commands.eval(
                ENQUEUE_SCRIPT,
                ScriptOutputType.VALUE,
                keys(
                        keyspace.taskWorkHash(item.taskId(), item.messageId()),
                        keyspace.taskLeaseHash(item.taskId(), item.messageId()),
                        keyspace.taskRegistrySet(),
                        keyspace.taskMembersSet(item.taskId()),
                        keyspace.taskStatsHash(item.taskId()),
                        keyspace.runtimeStatsHash(),
                        keyspace.delayedWorkZset(),
                        keyspace.taskDelayedZset(item.taskId()),
                        keyspace.taskReadyQueue(item.taskId()),
                        keyspace.readyTasksZset(),
                        keyspace.taskRecentFinalReceiptHash(item.taskId(), item.messageId()),
                        keyspace.taskRecentFinalReceiptSet(item.taskId()),
                        keyspace.recentFinalReceiptsZset()
                ),
                values(
                        item.taskId(),
                        item.messageId(),
                        nullToEmpty(item.eventCode()),
                        serializeMap(item.payload()),
                        nullToEmpty(item.payloadRef()),
                        Integer.toString(item.retryCount()),
                        Integer.toString(item.maxRetryCount()),
                        nullToEmpty(item.shardKey()),
                        Long.toString(item.nextVisibleAt() == null ? 0L : item.nextVisibleAt().toEpochMilli()),
                        Long.toString(item.createdAt().toEpochMilli()),
                        Long.toString(now.toEpochMilli()),
                        Integer.toString(options.maxReadyItemsPerTask()),
                        Integer.toString(maxQueuedItems),
                        keyspace.workMember(item.taskId(), item.messageId())
                )
        );
        String status = stringValue(outcome);
        return switch (status) {
            case "ENQUEUED" -> WorkEnqueueStatus.ENQUEUED;
            case "DUPLICATE" -> WorkEnqueueStatus.DUPLICATE;
            case "BACKPRESSURE_TASK", "BACKPRESSURE_GLOBAL" -> WorkEnqueueStatus.BACKPRESSURE_REJECTED;
            default -> WorkEnqueueStatus.FAILED;
        };
    }

    private List<ClaimedTaskWork> claimReadyAtomic(String taskId,
                                                   List<WorkerClaimTarget> workers,
                                                   TaskWorkClaimOptions options) {
        List<WorkerClaimTarget> claimPlan = buildClaimPlan(workers, options.maxItems());
        if (claimPlan.isEmpty()) {
            return List.of();
        }
        Instant now = clock.get();
        Instant leaseExpireAt = now.plusSeconds(Math.max(1L, options.leaseSeconds()));
        List<String> dueMessageIds = commands.zrangebyscore(
                keyspace.taskDelayedZset(taskId),
                Range.create(0D, (double) now.toEpochMilli()),
                io.lettuce.core.Limit.create(0, claimPlan.size())
        );
        List<String> args = new ArrayList<>();
        args.add(taskId);
        args.add(keyspace.taskPrefix(taskId));
        args.add(keyspace.namespace());
        args.add(Long.toString(now.toEpochMilli()));
        args.add(Long.toString(leaseExpireAt.toEpochMilli()));
        args.add(Integer.toString(dueMessageIds.size()));
        args.add(Integer.toString(claimPlan.size()));
        for (String messageId : dueMessageIds) {
            args.add(messageId);
            args.add(keyspace.workMember(taskId, messageId));
        }
        for (WorkerClaimTarget target : claimPlan) {
            String leaseToken = UUID.randomUUID().toString();
            args.add(target.workerId());
            args.add(nullToEmpty(target.batchId()));
            args.add(leaseToken);
            args.add(encodeSupportedEventCodes(target.supportedEventCodes()));
        }
        List<Object> rawClaimed = evalMulti(
                CLAIM_READY_SCRIPT,
                keys(
                        keyspace.taskReadyQueue(taskId),
                        keyspace.taskDelayedZset(taskId),
                        keyspace.readyTasksZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.taskActiveSet(taskId),
                        keyspace.leaseExpiryZset(),
                        keyspace.taskStatsHash(taskId),
                        keyspace.runtimeStatsHash()
                ),
                args
        );
        if (rawClaimed.isEmpty()) {
            return List.of();
        }
        List<ClaimedTaskWork> claimed = new ArrayList<>(rawClaimed.size() / 8);
        for (int i = 0; i + 7 < rawClaimed.size(); i += 8) {
            claimed.add(new ClaimedTaskWork(
                    taskId,
                    stringValue(rawClaimed.get(i)),
                    stringValue(rawClaimed.get(i + 1)),
                    stringValue(rawClaimed.get(i + 2)),
                    emptyToNull(stringValue(rawClaimed.get(i + 3))),
                    emptyToNull(stringValue(rawClaimed.get(i + 4))),
                    deserializeMap(stringValue(rawClaimed.get(i + 5))),
                    emptyToNull(stringValue(rawClaimed.get(i + 6))),
                    parseInt(stringValue(rawClaimed.get(i + 7))),
                    leaseExpireAt
            ));
        }
        return List.copyOf(claimed);
    }

    private ResultApplyOutcome applyResultAtomic(TaskWorkResult result) {
        Instant now = clock.get();
        Instant completedAt = result.completedAt() == null ? now : result.completedAt();
        String finalStatus = result.success()
                ? TaskWorkFinalStatus.SUCCESS.name()
                : result.expired() ? TaskWorkFinalStatus.EXPIRED.name() : TaskWorkFinalStatus.FAILED.name();
        List<Object> raw = evalMulti(
                APPLY_RESULT_SCRIPT,
                keys(
                        keyspace.taskWorkHash(result.taskId(), result.messageId()),
                        keyspace.taskLeaseHash(result.taskId(), result.messageId()),
                        keyspace.namespace() + ":worker:",
                        keyspace.taskActiveSet(result.taskId()),
                        keyspace.leaseExpiryZset(),
                        keyspace.taskMembersSet(result.taskId()),
                        keyspace.taskStatsHash(result.taskId()),
                        keyspace.runtimeStatsHash(),
                        keyspace.taskRecentFinalReceiptHash(result.taskId(), result.messageId()),
                        keyspace.taskRecentFinalReceiptSet(result.taskId()),
                        keyspace.recentFinalReceiptsZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.taskDelayedZset(result.taskId()),
                        keyspace.taskReadyQueue(result.taskId()),
                        keyspace.readyTasksZset()
                ),
                values(
                        result.taskId(),
                        result.messageId(),
                        keyspace.workMember(result.taskId(), result.messageId()),
                        nullToEmpty(result.leaseToken()),
                        Long.toString(now.toEpochMilli()),
                        Long.toString(completedAt.toEpochMilli()),
                        result.success() ? "1" : "0",
                        result.expired() ? "1" : "0",
                        result.retryable() ? "1" : "0",
                        Long.toString(result.retryVisibleAt() == null ? 0L : result.retryVisibleAt().toEpochMilli()),
                        nullToEmpty(result.errorCode()),
                        finalStatus,
                        keyspace.taskPrefix(result.taskId())
                )
        );
        String status = raw.isEmpty() ? "" : stringValue(raw.get(0));
        if ("SUCCESS_APPLIED".equals(status) || "FAILURE_FINALIZED".equals(status)) {
            trimRecentFinalReceipts();
        }
        return switch (status) {
            case "SUCCESS_APPLIED" -> ResultApplyOutcome.success(result);
            case "RETRY_SCHEDULED" -> ResultApplyOutcome.retryScheduled(result, "retry budget allows re-dispatch");
            case "FAILURE_FINALIZED" -> ResultApplyOutcome.failureFinalized(result, "retry budget exhausted or result is not retryable");
            case "STALE_LEASE" -> ResultApplyOutcome.staleLease(result, "result leaseToken does not match active lease");
            case "NO_ACTIVE_LEASE" -> ResultApplyOutcome.noActiveLease(result, "no active lease for result");
            default -> ResultApplyOutcome.failed(result, "unexpected applyResult status");
        };
    }

    /**
     * Executes {@link #APPLY_RESULT_WITH_CONTEXT_SCRIPT} and parses the 1- or
     * 8-element multi-bulk result into a {@link RuntimeResultApplyContext}.
     *
     * <p>Response layout (indices into {@code raw}):
     * <pre>
     *   [0] status string
     *   [1] workerId
     *   [2] batchId
     *   [3] activeLeaseToken
     *   [4] payloadRef
     *   [5] retryCount
     *   [6] maxRetryCount
     *   [7] leasedAtMillis
     * </pre>
     * A 1-element response means {@code NO_ACTIVE_LEASE}.</p>
     */
    private RuntimeResultApplyContext applyResultWithContextAtomic(TaskWorkResult result) {
        Instant now = clock.get();
        Instant completedAt = result.completedAt() == null ? now : result.completedAt();
        String finalStatus = result.success()
                ? TaskWorkFinalStatus.SUCCESS.name()
                : result.expired() ? TaskWorkFinalStatus.EXPIRED.name() : TaskWorkFinalStatus.FAILED.name();
        // Same KEYS/ARGV layout as applyResultAtomic 鈥?script handles the extra reads.
        List<Object> raw = evalMulti(
                APPLY_RESULT_WITH_CONTEXT_SCRIPT,
                keys(
                        keyspace.taskWorkHash(result.taskId(), result.messageId()),
                        keyspace.taskLeaseHash(result.taskId(), result.messageId()),
                        keyspace.namespace() + ":worker:",
                        keyspace.taskActiveSet(result.taskId()),
                        keyspace.leaseExpiryZset(),
                        keyspace.taskMembersSet(result.taskId()),
                        keyspace.taskStatsHash(result.taskId()),
                        keyspace.runtimeStatsHash(),
                        keyspace.taskRecentFinalReceiptHash(result.taskId(), result.messageId()),
                        keyspace.taskRecentFinalReceiptSet(result.taskId()),
                        keyspace.recentFinalReceiptsZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.taskDelayedZset(result.taskId()),
                        keyspace.taskReadyQueue(result.taskId()),
                        keyspace.readyTasksZset()
                ),
                values(
                        result.taskId(),
                        result.messageId(),
                        keyspace.workMember(result.taskId(), result.messageId()),
                        nullToEmpty(result.leaseToken()),
                        Long.toString(now.toEpochMilli()),
                        Long.toString(completedAt.toEpochMilli()),
                        result.success() ? "1" : "0",
                        result.expired() ? "1" : "0",
                        result.retryable() ? "1" : "0",
                        Long.toString(result.retryVisibleAt() == null ? 0L : result.retryVisibleAt().toEpochMilli()),
                        nullToEmpty(result.errorCode()),
                        finalStatus,
                        keyspace.taskPrefix(result.taskId())
                )
        );
        String status = raw.isEmpty() ? "" : stringValue(raw.get(0));
        // Trim receipts for terminal outcomes, same as applyResultAtomic.
        if ("SUCCESS_APPLIED".equals(status) || "FAILURE_FINALIZED".equals(status)) {
            trimRecentFinalReceipts();
        }
        ResultApplyOutcome outcome = switch (status) {
            case "SUCCESS_APPLIED" -> ResultApplyOutcome.success(result);
            case "RETRY_SCHEDULED" -> ResultApplyOutcome.retryScheduled(result, "retry budget allows re-dispatch");
            case "FAILURE_FINALIZED" -> ResultApplyOutcome.failureFinalized(result, "retry budget exhausted or result is not retryable");
            case "STALE_LEASE" -> ResultApplyOutcome.staleLease(result, "result leaseToken does not match active lease");
            case "NO_ACTIVE_LEASE" -> ResultApplyOutcome.noActiveLease(result, "no active lease for result");
            default -> ResultApplyOutcome.failed(result, "unexpected applyResultWithContext status: " + status);
        };
        // 1-element response = NO_ACTIVE_LEASE (or unexpected): no snapshot available.
        if (raw.size() < 8) {
            return RuntimeResultApplyContext.noLease(outcome);
        }
        return RuntimeResultApplyContext.withSnapshot(
                outcome,
                emptyToNull(stringValue(raw.get(1))),   // workerId
                emptyToNull(stringValue(raw.get(2))),   // batchId
                emptyToNull(stringValue(raw.get(3))),   // activeLeaseToken
                emptyToNull(stringValue(raw.get(4))),   // payloadRef
                parseInt(stringValue(raw.get(5))),      // retryCount
                parseInt(stringValue(raw.get(6))),      // maxRetryCount
                parseInstant(stringValue(raw.get(7)))   // leasedAt
        );
    }

    private List<ActiveLeaseRecord> pollExpiredLeasesAtomic(int limit, Instant now) {
        List<Object> raw = evalMulti(
                POLL_EXPIRED_LEASES_SCRIPT,
                keys(keyspace.leaseExpiryZset()),
                values(Long.toString(now.toEpochMilli()), Integer.toString(limit))
        );
        if (raw.isEmpty()) {
            return List.of();
        }
        List<ActiveLeaseRecord> expired = new ArrayList<>(raw.size());
        for (Object memberValue : raw) {
            RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(stringValue(memberValue));
            ActiveLeaseRecord lease = loadLease(ref.taskId(), ref.messageId());
            if (lease != null) {
                expired.add(lease);
            }
        }
        if (!expired.isEmpty()) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_EXPIRED_LEASE_ITEMS, expired.size());
        }
        return List.copyOf(expired);
    }

    private long discardTaskAtomic(String taskId) {
        Set<String> members = commands.smembers(keyspace.taskMembersSet(taskId));
        Set<String> recentFinalMembers = commands.smembers(keyspace.taskRecentFinalReceiptSet(taskId));
        List<String> args = new ArrayList<>();
        args.add(taskId);
        args.add(keyspace.taskPrefix(taskId));
        args.add(keyspace.namespace());
        args.add(Integer.toString(members.size()));
        args.add(Integer.toString(recentFinalMembers.size()));
        for (String messageId : members) {
            args.add(messageId);
            args.add(keyspace.workMember(taskId, messageId));
        }
        args.addAll(recentFinalMembers);
        Object outcome = commands.eval(
                DISCARD_TASK_SCRIPT,
                ScriptOutputType.VALUE,
                keys(
                        keyspace.taskReadyQueue(taskId),
                        keyspace.taskActiveSet(taskId),
                        keyspace.leaseExpiryZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.taskDelayedZset(taskId),
                        keyspace.taskStatsHash(taskId),
                        keyspace.taskMembersSet(taskId),
                        keyspace.taskRecentFinalReceiptSet(taskId),
                        keyspace.readyTasksZset(),
                        keyspace.taskRegistrySet(),
                        keyspace.runtimeStatsHash()
                ),
                args.toArray(String[]::new)
        );
        return parseLong(stringValue(outcome));
    }

    private void promoteDueDelayedLocked(Instant now, int batchSize) {
        int remaining = Math.max(1, batchSize);
        while (remaining > 0) {
            List<String> due = commands.zrangebyscore(
                    keyspace.delayedWorkZset(),
                    Range.create(0D, (double) now.toEpochMilli()),
                    io.lettuce.core.Limit.create(0, remaining)
            );
            if (due.isEmpty()) {
                return;
            }
            for (String member : due) {
                RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
                if (promoteDelayedMember(ref.taskId(), ref.messageId(), member, now)) {
                    remaining--;
                    if (remaining == 0) {
                        return;
                    }
                }
            }
        }
    }

    private void promoteDueDelayedForTaskLocked(String taskId, Instant now) {
        for (String messageId : commands.zrangebyscore(keyspace.taskDelayedZset(taskId), 0, now.toEpochMilli())) {
            promoteDelayedMember(taskId, messageId, keyspace.workMember(taskId, messageId), now);
        }
    }

    private boolean promoteDelayedMember(String taskId, String messageId, String workMember, Instant now) {
        TaskWorkEnvelope item = loadWork(taskId, messageId);
        commands.zrem(keyspace.delayedWorkZset(), workMember);
        commands.zrem(keyspace.taskDelayedZset(taskId), messageId);
        if (item == null || leaseExists(taskId, messageId)) {
            return false;
        }
        if (item.nextVisibleAt() != null && item.nextVisibleAt().isAfter(now)) {
            commands.zadd(keyspace.delayedWorkZset(), toScore(item.nextVisibleAt()), workMember);
            commands.zadd(keyspace.taskDelayedZset(taskId), toScore(item.nextVisibleAt()), messageId);
            return false;
        }
        decrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        commands.rpush(keyspace.taskReadyQueue(taskId), messageId);
        upsertReadyTaskScore(taskId, item.createdAt());
        incrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
        incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
        return true;
    }

    private List<String> loadReadyTaskIds(int limit) {
        List<String> visible = new ArrayList<>(limit);
        for (String taskId : commands.zrange(keyspace.readyTasksZset(), 0, Math.max(0, limit - 1))) {
            if (visible.size() >= limit) {
                break;
            }
            if (ensureReadyQueueVisible(taskId)) {
                visible.add(taskId);
            }
        }
        return List.copyOf(visible);
    }

    private boolean ensureReadyQueueVisible(String taskId) {
        while (commands.llen(keyspace.taskReadyQueue(taskId)) > 0) {
            String messageId = commands.lindex(keyspace.taskReadyQueue(taskId), 0);
            if (isBlank(messageId)) {
                break;
            }
            if (workExists(taskId, messageId) && !leaseExists(taskId, messageId)) {
                TaskWorkEnvelope item = loadWork(taskId, messageId);
                if (item != null) {
                    upsertReadyTaskScore(taskId, item.createdAt());
                    return true;
                }
            }
            commands.lpop(keyspace.taskReadyQueue(taskId));
            decrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
            decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
        }
        commands.zrem(keyspace.readyTasksZset(), taskId);
        return false;
    }

    private void trimRecentFinalReceipts() {
        long receiptCount = commands.zcard(keyspace.recentFinalReceiptsZset());
        long overflow = receiptCount - Math.max(1, maxRecentFinalReceipts);
        if (overflow <= 0) {
            return;
        }
        List<String> eldest = commands.zrange(keyspace.recentFinalReceiptsZset(), 0, overflow - 1);
        for (String member : eldest) {
            RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
            deleteRecentFinalReceipt(ref.taskId(), ref.messageId());
        }
    }

    private void deleteRecentFinalReceipt(String taskId, String messageId) {
        commands.del(keyspace.taskRecentFinalReceiptHash(taskId, messageId));
        commands.srem(keyspace.taskRecentFinalReceiptSet(taskId), messageId);
        commands.zrem(keyspace.recentFinalReceiptsZset(), keyspace.workMember(taskId, messageId));
    }

    private TaskWorkEnvelope loadWork(String taskId, String messageId) {
        Map<String, String> fields = commands.hgetall(keyspace.taskWorkHash(taskId, messageId));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return new TaskWorkEnvelope(
                taskId,
                messageId,
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_EVENT_CODE)),
                deserializeMap(fields.get(RedisTaskWorkKeyspace.FIELD_PAYLOAD_JSON)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_PAYLOAD_REF)),
                parseInt(fields.get(RedisTaskWorkKeyspace.FIELD_RETRY_COUNT)),
                parseInt(fields.get(RedisTaskWorkKeyspace.FIELD_MAX_RETRY_COUNT)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_SHARD_KEY)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_NEXT_VISIBLE_AT_MILLIS)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_CREATED_AT_MILLIS))
        );
    }

    private ActiveLeaseRecord loadLease(String taskId, String messageId) {
        Map<String, String> fields = commands.hgetall(keyspace.taskLeaseHash(taskId, messageId));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        return new ActiveLeaseRecord(
                taskId,
                messageId,
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_TOKEN)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_WORKER_ID)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_BATCH_ID)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_PAYLOAD_REF)),
                parseInt(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_RETRY_COUNT)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_EXPIRE_AT_MILLIS)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_LEASED_AT_MILLIS))
        );
    }

    private RecentFinalWorkReceipt loadRecentFinalReceipt(String taskId, String messageId) {
        Map<String, String> fields = commands.hgetall(keyspace.taskRecentFinalReceiptHash(taskId, messageId));
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String statusName = emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_FINAL_STATUS));
        if (statusName == null) {
            return null;
        }
        return new RecentFinalWorkReceipt(
                taskId,
                messageId,
                TaskWorkFinalStatus.valueOf(statusName),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_FINAL_ERROR_CODE)),
                parseInt(fields.get(RedisTaskWorkKeyspace.FIELD_FINAL_RETRY_COUNT)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_FINAL_COMPLETED_AT_MILLIS))
        );
    }

    private TaskWorkStats loadTaskStats(String taskId) {
        Map<String, String> fields = commands.hgetall(keyspace.taskStatsHash(taskId));
        if (fields == null || fields.isEmpty()) {
            return TaskWorkStats.EMPTY;
        }
        return new TaskWorkStats(
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_TOTAL_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_READY_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_SUCCESS_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_FAILED_COUNT)),
                parseLong(fields.get(RedisTaskWorkKeyspace.COUNTER_EXPIRED_COUNT))
        );
    }

    private void incrementRuntimeCounter(String counter, long delta) {
        if (delta != 0L) {
            commands.hincrby(keyspace.runtimeStatsHash(), counter, delta);
        }
    }

    private void incrementTaskCounter(String taskId, String counter, long delta) {
        if (delta != 0L) {
            commands.hincrby(keyspace.taskStatsHash(taskId), counter, delta);
        }
    }

    private void decrementRuntimeCounter(String counter, long delta) {
        if (delta <= 0L) {
            return;
        }
        long current = parseLong(commands.hget(keyspace.runtimeStatsHash(), counter));
        commands.hset(keyspace.runtimeStatsHash(), counter, Long.toString(Math.max(0L, current - delta)));
    }

    private void decrementTaskCounter(String taskId, String counter, long delta) {
        if (delta <= 0L) {
            return;
        }
        long current = parseLong(commands.hget(keyspace.taskStatsHash(taskId), counter));
        commands.hset(keyspace.taskStatsHash(taskId), counter, Long.toString(Math.max(0L, current - delta)));
    }

    private long oldestReadyAgeMillis() {
        List<String> readyTasks = commands.zrange(keyspace.readyTasksZset(), 0, 0);
        if (readyTasks.isEmpty()) {
            return 0L;
        }
        Double score = commands.zscore(keyspace.readyTasksZset(), readyTasks.get(0));
        if (score == null) {
            return 0L;
        }
        long oldestCreatedAt = Math.max(0L, score.longValue());
        return Math.max(0L, Duration.between(Instant.ofEpochMilli(oldestCreatedAt), clock.get()).toMillis());
    }

    private void upsertReadyTaskScore(String taskId, Instant createdAt) {
        double createdScore = toScore(createdAt);
        Double existing = commands.zscore(keyspace.readyTasksZset(), taskId);
        if (existing == null || createdScore < existing) {
            commands.zadd(keyspace.readyTasksZset(), createdScore, taskId);
        }
    }

    private boolean workExists(String taskId, String messageId) {
        return commands.exists(keyspace.taskWorkHash(taskId, messageId)) > 0;
    }

    private boolean leaseExists(String taskId, String messageId) {
        return commands.exists(keyspace.taskLeaseHash(taskId, messageId)) > 0;
    }

    private TaskWorkRuntimeStats emptyRuntimeStats() {
        return new TaskWorkRuntimeStats(0, 0, 0, 0, maxQueuedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private List<WorkerClaimTarget> buildClaimPlan(List<WorkerClaimTarget> workers, int maxItems) {
        List<WorkerCapacity> capacities = workers.stream()
                .filter(worker -> worker != null && !isBlank(worker.workerId()) && worker.capacity() > 0)
                .map(WorkerCapacity::new)
                .toList();
        if (capacities.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        List<WorkerClaimTarget> plan = new ArrayList<>(maxItems);
        int cursor = 0;
        while (plan.size() < maxItems) {
            WorkerCapacity capacity = nextCapacity(capacities, cursor);
            if (capacity == null) {
                break;
            }
            capacity.claimed++;
            plan.add(capacity.target);
            cursor = (capacities.indexOf(capacity) + 1) % capacities.size();
        }
        return List.copyOf(plan);
    }

    private WorkerCapacity nextCapacity(List<WorkerCapacity> capacities, int cursor) {
        for (int i = 0; i < capacities.size(); i++) {
            WorkerCapacity capacity = capacities.get((cursor + i) % capacities.size());
            if (capacity.hasCapacity()) {
                return capacity;
            }
        }
        return null;
    }

    private List<Object> evalMulti(String script, String[] keys, String[] values) {
        Object raw = commands.eval(script, ScriptOutputType.MULTI, keys, values);
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<Object> evalMulti(String script, String[] keys, List<String> values) {
        return evalMulti(script, keys, values.toArray(String[]::new));
    }

    private String[] keys(String... keys) {
        return keys;
    }

    private String[] values(String... values) {
        return values;
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

    private static Instant parseInstant(String value) {
        if (isBlank(value)) {
            return null;
        }
        return Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static Map<String, Object> deserializeMap(String json) {
        if (isBlank(json)) {
            return Map.of();
        }
        Map<String, Object> payload = GSON.fromJson(json, MAP_TYPE);
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static String serializeMap(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        return GSON.toJson(payload);
    }

    private static long parseLong(String value) {
        if (isBlank(value)) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private static int parseInt(String value) {
        if (isBlank(value)) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : Objects.toString(value, "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String encodeSupportedEventCodes(Set<String> eventCodes) {
        if (eventCodes == null || eventCodes.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(EVENT_SCOPE_SEPARATOR);
        for (String eventCode : eventCodes) {
            if (eventCode == null || eventCode.isBlank()) {
                continue;
            }
            builder.append(eventCode.trim()).append(EVENT_SCOPE_SEPARATOR);
        }
        return builder.length() == 1 ? "" : builder.toString();
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class WorkerCapacity {
        private final WorkerClaimTarget target;
        private int claimed;

        private WorkerCapacity(WorkerClaimTarget target) {
            this.target = target;
        }

        private boolean hasCapacity() {
            return claimed < target.capacity();
        }
    }
}
