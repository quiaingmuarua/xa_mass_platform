package com.xa.mass.runtime.redis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.TaskWorkClaimOptions;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkRuntimeStats;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
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
 * <p>This first slice keeps one explicit runtime mutex so XA Mass can use real
 * Redis queue/lease truth without reintroducing engine-local runtime state.
 * The lock keeps semantics coherent while the module converges toward finer
 * Lua-scripted hot paths.</p>
 */
public final class RedisTaskWorkRuntime implements TaskWorkRuntime {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    private static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 5_000L;
    private static final long DEFAULT_LOCK_LEASE_MILLIS = 10_000L;
    private static final long LOCK_RETRY_SLEEP_MILLIS = 10L;
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final RedisTaskWorkKeyspace keyspace;
    private final Supplier<Instant> clock;
    private final int maxQueuedItems;
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
        this.ownsClient = ownsClient;
    }

    @Override
    public WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options) {
        if (!running.get()) {
            return WorkEnqueueOutcome.unavailable(item, "work runtime is stopped");
        }
        return withRuntimeLock(() -> enqueueLocked(item, options));
    }

    @Override
    public List<String> readyTaskIds(int limit) {
        if (limit <= 0 || !running.get()) {
            return List.of();
        }
        return withRuntimeLock(() -> {
            promoteDueDelayedLocked(clock.get(), limit);
            return loadReadyTaskIdsLocked(limit);
        });
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
        return withRuntimeLock(() -> claimReadyLocked(taskId, workers, options));
    }

    @Override
    public ResultApplyOutcome applyResult(TaskWorkResult result) {
        if (!running.get()) {
            return ResultApplyOutcome.failed(result, "work runtime is stopped");
        }
        return withRuntimeLock(() -> applyResultLocked(result));
    }

    @Override
    public List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        return withRuntimeLock(() -> pollExpiredLeasesLocked(limit, now == null ? clock.get() : now));
    }

    @Override
    public List<ActiveLeaseRecord> activeLeases(String taskId) {
        if (!running.get() || isBlank(taskId)) {
            return List.of();
        }
        return withRuntimeLock(() -> activeLeasesLocked(taskId));
    }

    @Override
    public Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        return withRuntimeLock(() -> Optional.ofNullable(loadLease(taskId, messageId)));
    }

    @Override
    public boolean hasReadyWork(String taskId) {
        if (isBlank(taskId) || !running.get()) {
            return false;
        }
        return withRuntimeLock(() -> {
            promoteDueDelayedForTaskLocked(taskId, clock.get());
            return ensureReadyQueueVisibleLocked(taskId);
        });
    }

    @Override
    public boolean hasActiveLeaseForWorker(String taskId, String workerId) {
        if (isBlank(taskId) || isBlank(workerId) || !running.get()) {
            return false;
        }
        return withRuntimeLock(() -> {
            for (String member : commands.smembers(keyspace.workerActiveSet(workerId))) {
                RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
                if (taskId.equals(ref.taskId()) && loadLease(ref.taskId(), ref.messageId()) != null) {
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public TaskWorkStats stats(String taskId) {
        if (!running.get()) {
            return TaskWorkStats.EMPTY;
        }
        if (isBlank(taskId)) {
            return TaskWorkStats.EMPTY;
        }
        return withRuntimeLock(() -> {
            promoteDueDelayedForTaskLocked(taskId, clock.get());
            return loadTaskStats(taskId);
        });
    }

    @Override
    public TaskWorkRuntimeStats stats() {
        if (!running.get()) {
            return new TaskWorkRuntimeStats(0, 0, 0, 0, maxQueuedItems, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return withRuntimeLock(() -> {
            promoteDueDelayedLocked(clock.get(), 256);
            Map<String, String> runtimeStats = commands.hgetall(keyspace.runtimeStatsHash());
            return new TaskWorkRuntimeStats(
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_READY_COUNT)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT)),
                    parseLong(runtimeStats.get(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT)),
                    Math.toIntExact(commands.zcard(keyspace.readyTasksZset())),
                    maxQueuedItems,
                    oldestReadyAgeMillisLocked(),
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
        });
    }

    @Override
    public long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        return withRuntimeLock(() -> discardTaskLocked(taskId));
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            closeRedisResources();
            return;
        }
        try {
            withRuntimeLock(() -> {
                long cleared = 0L;
                for (String taskId : commands.smembers(keyspace.taskRegistrySet())) {
                    cleared += discardTaskLocked(taskId);
                }
                if (cleared > 0) {
                    incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_SHUTDOWN_CLEARED_ITEMS, cleared);
                }
                commands.del(
                        keyspace.readyTasksZset(),
                        keyspace.delayedWorkZset(),
                        keyspace.leaseExpiryZset(),
                        keyspace.taskRegistrySet()
                );
                return null;
            });
        } finally {
            closeRedisResources();
        }
    }

    private WorkEnqueueOutcome enqueueLocked(TaskWorkEnvelope item, WorkEnqueueOptions options) {
        if (!running.get()) {
            return WorkEnqueueOutcome.unavailable(item, "work runtime is stopped");
        }
        if (item == null || isBlank(item.taskId()) || isBlank(item.messageId())) {
            return WorkEnqueueOutcome.invalid(item, "taskId and messageId must not be blank");
        }
        if (workExists(item.taskId(), item.messageId()) || leaseExists(item.taskId(), item.messageId())) {
            return WorkEnqueueOutcome.duplicate(item, "work item already exists");
        }
        int maxReadyItemsPerTask = options == null
                ? WorkEnqueueOptions.DEFAULT.maxReadyItemsPerTask()
                : options.maxReadyItemsPerTask();
        TaskWorkStats taskStats = loadTaskStats(item.taskId());
        if (taskStats.readyCount() >= maxReadyItemsPerTask) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_BACKPRESSURE_REJECTED_ITEMS, 1L);
            return WorkEnqueueOutcome.backpressureRejected(item, "task ready backlog is full");
        }
        long globalQueued = runtimeGauge(RedisTaskWorkKeyspace.COUNTER_READY_COUNT)
                + runtimeGauge(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT);
        if (globalQueued >= maxQueuedItems) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_BACKPRESSURE_REJECTED_ITEMS, 1L);
            return WorkEnqueueOutcome.backpressureRejected(item, "engine work backlog is full");
        }

        writeWorkHash(item);
        commands.sadd(keyspace.taskRegistrySet(), item.taskId());
        commands.sadd(keyspace.taskMembersSet(item.taskId()), item.messageId());
        incrementTaskCounter(item.taskId(), RedisTaskWorkKeyspace.COUNTER_TOTAL_COUNT, 1L);
        incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_ENQUEUED_ITEMS, 1L);

        Instant now = clock.get();
        if (item.nextVisibleAt() != null && item.nextVisibleAt().isAfter(now)) {
            moveToDelayedLocked(item.taskId(), item.messageId(), item.nextVisibleAt(), item.createdAt(), false);
        } else {
            moveToReadyLocked(item.taskId(), item.messageId(), item.createdAt(), false);
        }
        return WorkEnqueueOutcome.enqueued(item);
    }

    private List<String> loadReadyTaskIdsLocked(int limit) {
        List<String> visible = new ArrayList<>(limit);
        for (String taskId : commands.zrange(keyspace.readyTasksZset(), 0, Math.max(0, limit - 1))) {
            if (visible.size() >= limit) {
                break;
            }
            if (ensureReadyQueueVisibleLocked(taskId)) {
                visible.add(taskId);
            }
        }
        return List.copyOf(visible);
    }

    private List<ClaimedTaskWork> claimReadyLocked(String taskId,
                                                   List<WorkerClaimTarget> workers,
                                                   TaskWorkClaimOptions options) {
        promoteDueDelayedForTaskLocked(taskId, clock.get());
        if (!ensureReadyQueueVisibleLocked(taskId)) {
            return List.of();
        }
        List<WorkerCapacity> capacities = workers.stream()
                .filter(worker -> worker != null && !isBlank(worker.workerId()) && worker.capacity() > 0)
                .map(WorkerCapacity::new)
                .toList();
        if (capacities.isEmpty()) {
            return List.of();
        }

        List<ClaimedTaskWork> claimed = new ArrayList<>(Math.min(options.maxItems(), capacities.size()));
        Instant leasedAt = clock.get();
        Instant leaseExpireAt = leasedAt.plusSeconds(Math.max(1L, options.leaseSeconds()));
        int cursor = 0;
        while (claimed.size() < options.maxItems() && ensureReadyQueueVisibleLocked(taskId)) {
            WorkerCapacity capacity = nextCapacity(capacities, cursor);
            if (capacity == null) {
                break;
            }
            cursor = (capacities.indexOf(capacity) + 1) % capacities.size();
            String messageId = commands.lpop(keyspace.taskReadyQueue(taskId));
            if (isBlank(messageId)) {
                commands.zrem(keyspace.readyTasksZset(), taskId);
                break;
            }
            TaskWorkEnvelope item = loadWork(taskId, messageId);
            if (item == null || leaseExists(taskId, messageId)) {
                continue;
            }
            decrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
            decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);

            String leaseToken = UUID.randomUUID().toString();
            ActiveLeaseRecord lease = new ActiveLeaseRecord(
                    taskId,
                    messageId,
                    leaseToken,
                    capacity.target.workerId(),
                    capacity.target.workerContextId(),
                    capacity.target.batchId(),
                    item.retryCount(),
                    leaseExpireAt,
                    leasedAt
            );
            writeLeaseHash(lease);
            String workMember = keyspace.workMember(taskId, messageId);
            commands.sadd(keyspace.taskActiveSet(taskId), workMember);
            commands.sadd(keyspace.workerActiveSet(lease.workerId()), workMember);
            commands.zadd(keyspace.leaseExpiryZset(), toScore(leaseExpireAt), workMember);
            incrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT, 1L);
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT, 1L);
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_CLAIMED_ITEMS, 1L);
            claimed.add(new ClaimedTaskWork(
                    taskId,
                    messageId,
                    leaseToken,
                    lease.workerId(),
                    lease.workerContextId(),
                    lease.batchId(),
                    item.eventCode(),
                    item.payload(),
                    item.payloadRef(),
                    item.retryCount(),
                    leaseExpireAt
            ));
        }
        if (commands.llen(keyspace.taskReadyQueue(taskId)) <= 0) {
            commands.zrem(keyspace.readyTasksZset(), taskId);
        }
        return List.copyOf(claimed);
    }

    private ResultApplyOutcome applyResultLocked(TaskWorkResult result) {
        if (!running.get()) {
            return ResultApplyOutcome.failed(result, "work runtime is stopped");
        }
        if (result == null || isBlank(result.taskId()) || isBlank(result.messageId())) {
            return ResultApplyOutcome.invalid(result, "taskId and messageId must not be blank");
        }

        ActiveLeaseRecord lease = loadLease(result.taskId(), result.messageId());
        if (lease == null) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DUPLICATE_RESULT_ITEMS, 1L);
            return ResultApplyOutcome.noActiveLease(result, "no active lease for result");
        }
        if (!isBlank(result.leaseToken()) && !result.leaseToken().equals(lease.leaseToken())) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_STALE_RESULT_ITEMS, 1L);
            return ResultApplyOutcome.staleLease(result, "result leaseToken does not match active lease");
        }

        removeLeaseLocked(lease);
        decrementTaskCounter(result.taskId(), RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT, 1L);
        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT, 1L);
        incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_RESULT_APPLIED_ITEMS, 1L);

        if (result.success()) {
            deleteWorkLocked(result.taskId(), result.messageId());
            incrementTaskCounter(result.taskId(), RedisTaskWorkKeyspace.COUNTER_SUCCESS_COUNT, 1L);
            return ResultApplyOutcome.success(result);
        }

        TaskWorkEnvelope item = loadWork(result.taskId(), result.messageId());
        boolean canRetry = result.retryable()
                && item != null
                && item.retryCount() < item.maxRetryCount();
        if (canRetry) {
            Instant now = clock.get();
            Instant nextVisibleAt = resolveNextRetryVisibleAt(result, now);
            TaskWorkEnvelope retry = item.withRetry(item.retryCount() + 1, nextVisibleAt);
            writeWorkHash(retry);
            if (nextVisibleAt.isAfter(now)) {
                moveToDelayedLocked(retry.taskId(), retry.messageId(), nextVisibleAt, retry.createdAt(), false);
            } else {
                moveToReadyLocked(retry.taskId(), retry.messageId(), retry.createdAt(), false);
            }
            return ResultApplyOutcome.retryScheduled(result, "retry budget allows re-dispatch");
        }

        deleteWorkLocked(result.taskId(), result.messageId());
        if (result.expired()) {
            incrementTaskCounter(result.taskId(), RedisTaskWorkKeyspace.COUNTER_EXPIRED_COUNT, 1L);
        } else {
            incrementTaskCounter(result.taskId(), RedisTaskWorkKeyspace.COUNTER_FAILED_COUNT, 1L);
        }
        return ResultApplyOutcome.failureFinalized(result, "retry budget exhausted or result is not retryable");
    }

    private List<ActiveLeaseRecord> pollExpiredLeasesLocked(int limit, Instant now) {
        List<ActiveLeaseRecord> expired = new ArrayList<>(limit);
        for (String member : commands.zrangebyscore(
                keyspace.leaseExpiryZset(),
                Range.create(0D, toScore(now)),
                io.lettuce.core.Limit.create(0, limit))) {
            if (expired.size() >= limit) {
                break;
            }
            commands.zrem(keyspace.leaseExpiryZset(), member);
            RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
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

    private List<ActiveLeaseRecord> activeLeasesLocked(String taskId) {
        List<ActiveLeaseRecord> leases = new ArrayList<>();
        for (String member : commands.smembers(keyspace.taskActiveSet(taskId))) {
            RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
            ActiveLeaseRecord lease = loadLease(ref.taskId(), ref.messageId());
            if (lease != null) {
                leases.add(lease);
            }
        }
        return List.copyOf(leases);
    }

    private long discardTaskLocked(String taskId) {
        TaskWorkStats stats = loadTaskStats(taskId);
        Set<String> members = commands.smembers(keyspace.taskMembersSet(taskId));
        Set<String> activeMembers = commands.smembers(keyspace.taskActiveSet(taskId));
        long discarded = 0L;

        for (String activeMember : activeMembers) {
            RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(activeMember);
            ActiveLeaseRecord lease = loadLease(ref.taskId(), ref.messageId());
            if (lease != null) {
                removeLeaseLocked(lease);
            }
        }

        for (String messageId : members) {
            String workHash = keyspace.taskWorkHash(taskId, messageId);
            if (commands.exists(workHash) > 0) {
                discarded++;
            }
            commands.del(workHash, keyspace.taskLeaseHash(taskId, messageId));
            commands.zrem(keyspace.delayedWorkZset(), keyspace.workMember(taskId, messageId));
        }

        commands.del(
                keyspace.taskReadyQueue(taskId),
                keyspace.taskDelayedZset(taskId),
                keyspace.taskActiveSet(taskId),
                keyspace.taskMembersSet(taskId),
                keyspace.taskStatsHash(taskId)
        );
        commands.zrem(keyspace.readyTasksZset(), taskId);
        commands.srem(keyspace.taskRegistrySet(), taskId);

        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, stats.readyCount());
        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, stats.delayedCount());
        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_INFLIGHT_COUNT, stats.inflightCount());
        if (discarded > 0) {
            incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DISCARDED_ITEMS, discarded);
        }
        return discarded;
    }

    private void promoteDueDelayedLocked(Instant now, int batchSize) {
        int remaining = Math.max(1, batchSize);
        while (remaining > 0) {
            List<String> due = commands.zrangebyscore(
                    keyspace.delayedWorkZset(),
                    Range.create(0D, toScore(now)),
                    io.lettuce.core.Limit.create(0, remaining)
            );
            if (due.isEmpty()) {
                return;
            }
            for (String member : due) {
                RedisTaskWorkKeyspace.WorkRef ref = keyspace.parseWorkMember(member);
                if (promoteDelayedMemberLocked(ref.taskId(), ref.messageId(), now)) {
                    remaining--;
                    if (remaining == 0) {
                        return;
                    }
                }
            }
        }
    }

    private void promoteDueDelayedForTaskLocked(String taskId, Instant now) {
        for (String messageId : commands.zrangebyscore(keyspace.taskDelayedZset(taskId), 0, toScore(now))) {
            promoteDelayedMemberLocked(taskId, messageId, now);
        }
    }

    private boolean promoteDelayedMemberLocked(String taskId, String messageId, Instant now) {
        TaskWorkEnvelope item = loadWork(taskId, messageId);
        String member = keyspace.workMember(taskId, messageId);
        commands.zrem(keyspace.delayedWorkZset(), member);
        commands.zrem(keyspace.taskDelayedZset(taskId), messageId);
        if (item == null || leaseExists(taskId, messageId)) {
            return false;
        }
        if (item.nextVisibleAt() != null && item.nextVisibleAt().isAfter(now)) {
            commands.zadd(keyspace.delayedWorkZset(), toScore(item.nextVisibleAt()), member);
            commands.zadd(keyspace.taskDelayedZset(taskId), toScore(item.nextVisibleAt()), messageId);
            return false;
        }
        decrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        moveToReadyLocked(taskId, messageId, item.createdAt(), true);
        return true;
    }

    private void moveToReadyLocked(String taskId, String messageId, Instant createdAt, boolean fromDelayed) {
        commands.rpush(keyspace.taskReadyQueue(taskId), messageId);
        upsertReadyTaskScore(taskId, createdAt);
        incrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
        incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
        if (fromDelayed) {
            return;
        }
    }

    private void moveToDelayedLocked(String taskId,
                                     String messageId,
                                     Instant nextVisibleAt,
                                     Instant createdAt,
                                     boolean fromReady) {
        String member = keyspace.workMember(taskId, messageId);
        commands.zadd(keyspace.delayedWorkZset(), toScore(nextVisibleAt), member);
        commands.zadd(keyspace.taskDelayedZset(taskId), toScore(nextVisibleAt), messageId);
        incrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        incrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_DELAYED_COUNT, 1L);
        if (fromReady) {
            decrementTaskCounter(taskId, RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
            decrementRuntimeCounter(RedisTaskWorkKeyspace.COUNTER_READY_COUNT, 1L);
            upsertReadyTaskScore(taskId, createdAt);
        }
    }

    private boolean ensureReadyQueueVisibleLocked(String taskId) {
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
        }
        commands.zrem(keyspace.readyTasksZset(), taskId);
        return false;
    }

    private void removeLeaseLocked(ActiveLeaseRecord lease) {
        String workMember = keyspace.workMember(lease.taskId(), lease.messageId());
        commands.del(keyspace.taskLeaseHash(lease.taskId(), lease.messageId()));
        commands.srem(keyspace.taskActiveSet(lease.taskId()), workMember);
        commands.srem(keyspace.workerActiveSet(lease.workerId()), workMember);
        commands.zrem(keyspace.leaseExpiryZset(), workMember);
    }

    private void deleteWorkLocked(String taskId, String messageId) {
        commands.del(keyspace.taskWorkHash(taskId, messageId));
        commands.srem(keyspace.taskMembersSet(taskId), messageId);
    }

    private void writeWorkHash(TaskWorkEnvelope item) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisTaskWorkKeyspace.FIELD_EVENT_CODE, nullToEmpty(item.eventCode()));
        fields.put(RedisTaskWorkKeyspace.FIELD_PAYLOAD_JSON, serializeMap(item.payload()));
        fields.put(RedisTaskWorkKeyspace.FIELD_PAYLOAD_REF, nullToEmpty(item.payloadRef()));
        fields.put(RedisTaskWorkKeyspace.FIELD_RETRY_COUNT, Integer.toString(item.retryCount()));
        fields.put(RedisTaskWorkKeyspace.FIELD_MAX_RETRY_COUNT, Integer.toString(item.maxRetryCount()));
        fields.put(RedisTaskWorkKeyspace.FIELD_SHARD_KEY, nullToEmpty(item.shardKey()));
        fields.put(RedisTaskWorkKeyspace.FIELD_NEXT_VISIBLE_AT_MILLIS, instantToString(item.nextVisibleAt()));
        fields.put(RedisTaskWorkKeyspace.FIELD_CREATED_AT_MILLIS, instantToString(item.createdAt()));
        commands.hset(keyspace.taskWorkHash(item.taskId(), item.messageId()), fields);
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

    private void writeLeaseHash(ActiveLeaseRecord lease) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisTaskWorkKeyspace.FIELD_LEASE_TOKEN, lease.leaseToken());
        fields.put(RedisTaskWorkKeyspace.FIELD_WORKER_ID, nullToEmpty(lease.workerId()));
        fields.put(RedisTaskWorkKeyspace.FIELD_WORKER_CONTEXT_ID, nullToEmpty(lease.workerContextId()));
        fields.put(RedisTaskWorkKeyspace.FIELD_BATCH_ID, nullToEmpty(lease.batchId()));
        fields.put(RedisTaskWorkKeyspace.FIELD_LEASE_RETRY_COUNT, Integer.toString(lease.retryCount()));
        fields.put(RedisTaskWorkKeyspace.FIELD_LEASE_EXPIRE_AT_MILLIS, instantToString(lease.leaseExpireAt()));
        fields.put(RedisTaskWorkKeyspace.FIELD_LEASED_AT_MILLIS, instantToString(lease.leasedAt()));
        commands.hset(keyspace.taskLeaseHash(lease.taskId(), lease.messageId()), fields);
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
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_WORKER_CONTEXT_ID)),
                emptyToNull(fields.get(RedisTaskWorkKeyspace.FIELD_BATCH_ID)),
                parseInt(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_RETRY_COUNT)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_LEASE_EXPIRE_AT_MILLIS)),
                parseInstant(fields.get(RedisTaskWorkKeyspace.FIELD_LEASED_AT_MILLIS))
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

    private void incrementTaskCounter(String taskId, String counter, long delta) {
        if (delta != 0L) {
            commands.hincrby(keyspace.taskStatsHash(taskId), counter, delta);
        }
    }

    private void decrementTaskCounter(String taskId, String counter, long delta) {
        if (delta <= 0L) {
            return;
        }
        long current = parseLong(commands.hget(keyspace.taskStatsHash(taskId), counter));
        long next = Math.max(0L, current - delta);
        commands.hset(keyspace.taskStatsHash(taskId), counter, Long.toString(next));
    }

    private void incrementRuntimeCounter(String counter, long delta) {
        if (delta != 0L) {
            commands.hincrby(keyspace.runtimeStatsHash(), counter, delta);
        }
    }

    private void decrementRuntimeCounter(String counter, long delta) {
        if (delta <= 0L) {
            return;
        }
        long current = runtimeGauge(counter);
        long next = Math.max(0L, current - delta);
        commands.hset(keyspace.runtimeStatsHash(), counter, Long.toString(next));
    }

    private long runtimeGauge(String counter) {
        return parseLong(commands.hget(keyspace.runtimeStatsHash(), counter));
    }

    private void upsertReadyTaskScore(String taskId, Instant createdAt) {
        double createdScore = toScore(createdAt);
        Double existing = commands.zscore(keyspace.readyTasksZset(), taskId);
        if (existing == null || createdScore < existing) {
            commands.zadd(keyspace.readyTasksZset(), createdScore, taskId);
        }
    }

    private long oldestReadyAgeMillisLocked() {
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

    private boolean workExists(String taskId, String messageId) {
        return commands.exists(keyspace.taskWorkHash(taskId, messageId)) > 0;
    }

    private boolean leaseExists(String taskId, String messageId) {
        return commands.exists(keyspace.taskLeaseHash(taskId, messageId)) > 0;
    }

    private <T> T withRuntimeLock(LockedSupplier<T> supplier) {
        String token = acquireLock();
        try {
            return supplier.get();
        } finally {
            releaseLock(token);
        }
    }

    private String acquireLock() {
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + DEFAULT_LOCK_TIMEOUT_MILLIS;
        while (true) {
            String result = commands.set(lockKey(), token, SetArgs.Builder.nx().px(DEFAULT_LOCK_LEASE_MILLIS));
            if ("OK".equalsIgnoreCase(result)) {
                return token;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Timed out acquiring Redis task-work runtime lock");
            }
            sleepQuietly();
        }
    }

    private void releaseLock(String token) {
        String current = commands.get(lockKey());
        if (Objects.equals(current, token)) {
            commands.del(lockKey());
        }
    }

    private String lockKey() {
        return keyspace.namespace() + ":lock:runtime";
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

    private void sleepQuietly() {
        try {
            Thread.sleep(LOCK_RETRY_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Redis task-work runtime lock", ex);
        }
    }

    private Instant resolveNextRetryVisibleAt(TaskWorkResult result, Instant now) {
        if (result == null || result.retryVisibleAt() == null || !result.retryVisibleAt().isAfter(now)) {
            return now;
        }
        return result.retryVisibleAt();
    }

    private static double toScore(Instant instant) {
        return instant == null ? 0D : instant.toEpochMilli();
    }

    private static String instantToString(Instant instant) {
        return instant == null ? "" : Long.toString(instant.toEpochMilli());
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

    private WorkerCapacity nextCapacity(List<WorkerCapacity> capacities, int cursor) {
        for (int i = 0; i < capacities.size(); i++) {
            WorkerCapacity capacity = capacities.get((cursor + i) % capacities.size());
            if (capacity.hasCapacity()) {
                capacity.claimed++;
                return capacity;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface LockedSupplier<T> {
        T get();
    }
}
