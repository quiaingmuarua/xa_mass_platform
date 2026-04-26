package com.xa.mass.engine.work;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory work runtime for the engine scheduling hot path.
 *
 * <p>This implementation intentionally models queue, lease, retry, and counter
 * semantics rather than exposing collection operations. A Redis/JDBC
 * implementation should preserve these method-level semantics.</p>
 */
public final class InMemoryTaskWorkRuntime implements TaskWorkRuntime {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;

    private final Map<String, ArrayDeque<WorkKey>> readyByTask = new HashMap<>();
    private final Map<WorkKey, TaskWorkEnvelope> workByKey = new HashMap<>();
    private final Map<WorkKey, ActiveLeaseRecord> leaseByKey = new HashMap<>();
    private final Map<String, Set<WorkKey>> activeByWorker = new HashMap<>();
    private final PriorityQueue<LeaseDeadline> leaseExpiryIndex =
            new PriorityQueue<>(Comparator.comparing(LeaseDeadline::expireAt));
    private final PriorityQueue<DelayedWork> delayedRetryIndex =
            new PriorityQueue<>(Comparator.comparing(DelayedWork::visibleAt));
    private final Map<String, MutableTaskStats> statsByTask = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong enqueuedItems = new AtomicLong();
    private final AtomicLong claimedItems = new AtomicLong();
    private final AtomicLong resultAppliedItems = new AtomicLong();
    private final AtomicLong backpressureRejectedItems = new AtomicLong();
    private final AtomicLong duplicateResultItems = new AtomicLong();
    private final AtomicLong staleResultItems = new AtomicLong();
    private final AtomicLong expiredLeaseItems = new AtomicLong();
    private final AtomicLong discardedItems = new AtomicLong();
    private final AtomicLong shutdownClearedItems = new AtomicLong();
    private final int maxQueuedItems;
    private final Supplier<Instant> clock;
    private long readyItems;
    private long delayedItems;

    public InMemoryTaskWorkRuntime() {
        this(DEFAULT_MAX_QUEUED_ITEMS);
    }

    public InMemoryTaskWorkRuntime(int maxQueuedItems) {
        this(maxQueuedItems, Instant::now);
    }

    InMemoryTaskWorkRuntime(int maxQueuedItems, Supplier<Instant> clock) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        this.maxQueuedItems = maxQueuedItems;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized WorkEnqueueOutcome enqueue(TaskWorkEnvelope item, WorkEnqueueOptions options) {
        if (!running.get()) {
            return WorkEnqueueOutcome.unavailable(item, "work runtime is stopped");
        }
        if (item == null || isBlank(item.taskId()) || isBlank(item.messageId())) {
            return WorkEnqueueOutcome.invalid(item, "taskId and messageId must not be blank");
        }
        WorkKey key = WorkKey.from(item);
        if (workByKey.containsKey(key) || leaseByKey.containsKey(key)) {
            return WorkEnqueueOutcome.duplicate(item, "work item already exists");
        }
        int maxReadyItemsPerTask = options == null
                ? WorkEnqueueOptions.DEFAULT.maxReadyItemsPerTask()
                : options.maxReadyItemsPerTask();
        MutableTaskStats taskStats = mutableStats(item.taskId());
        if (taskStats.readyCount >= maxReadyItemsPerTask) {
            backpressureRejectedItems.incrementAndGet();
            return WorkEnqueueOutcome.backpressureRejected(item, "task ready backlog is full");
        }
        if (readyItems + delayedItems >= maxQueuedItems) {
            backpressureRejectedItems.incrementAndGet();
            return WorkEnqueueOutcome.backpressureRejected(item, "engine work backlog is full");
        }
        workByKey.put(key, item);
        Instant now = clock.get();
        if (item.nextVisibleAt() != null && item.nextVisibleAt().isAfter(now)) {
            delayedRetryIndex.add(new DelayedWork(key, item.nextVisibleAt()));
            delayedItems++;
            taskStats.delayedCount++;
        } else {
            readyByTask.computeIfAbsent(item.taskId(), ignored -> new ArrayDeque<>()).addLast(key);
            readyItems++;
            taskStats.readyCount++;
        }
        enqueuedItems.incrementAndGet();
        return WorkEnqueueOutcome.enqueued(item);
    }

    @Override
    public synchronized List<ClaimedTaskWork> claimReady(String taskId,
                                                        List<WorkerClaimTarget> workers,
                                                        int maxItems,
                                                        long leaseSeconds) {
        if (!running.get() || isBlank(taskId) || workers == null || workers.isEmpty() || maxItems <= 0) {
            return List.of();
        }
        promoteDueDelayed(clock.get());
        ArrayDeque<WorkKey> ready = readyByTask.get(taskId);
        if (ready == null || ready.isEmpty()) {
            return List.of();
        }
        List<WorkerCapacity> capacities = workers.stream()
                .filter(worker -> worker != null && !isBlank(worker.workerId()) && worker.capacity() > 0)
                .map(WorkerCapacity::new)
                .toList();
        if (capacities.isEmpty()) {
            return List.of();
        }

        List<ClaimedTaskWork> claimed = new ArrayList<>(Math.min(maxItems, ready.size()));
        int workerCursor = 0;
        Instant leasedAt = clock.get();
        Instant leaseExpireAt = leasedAt.plusSeconds(Math.max(1L, leaseSeconds));
        while (!ready.isEmpty() && claimed.size() < maxItems && hasCapacity(capacities)) {
            WorkKey key = ready.pollFirst();
            TaskWorkEnvelope item = workByKey.get(key);
            if (item == null || leaseByKey.containsKey(key)) {
                decrementReady(taskId);
                continue;
            }
            if (item.nextVisibleAt() != null && item.nextVisibleAt().isAfter(leasedAt)) {
                delayedRetryIndex.add(new DelayedWork(key, item.nextVisibleAt()));
                MutableTaskStats taskStats = mutableStats(taskId);
                decrementReady(taskId);
                delayedItems++;
                taskStats.delayedCount++;
                continue;
            }
            WorkerCapacity capacity = nextCapacity(capacities, workerCursor);
            if (capacity == null) {
                ready.addFirst(key);
                break;
            }
            workerCursor = capacities.indexOf(capacity) + 1;
            capacity.claimed++;
            decrementReady(taskId);
            String leaseToken = UUID.randomUUID().toString();
            ActiveLeaseRecord lease = new ActiveLeaseRecord(
                    taskId,
                    item.messageId(),
                    leaseToken,
                    capacity.target.workerId(),
                    capacity.target.workerContextId(),
                    capacity.target.batchId(),
                    item.retryCount(),
                    leaseExpireAt,
                    leasedAt
            );
            leaseByKey.put(key, lease);
            activeByWorker.computeIfAbsent(lease.workerId(), ignored -> new HashSet<>()).add(key);
            leaseExpiryIndex.add(new LeaseDeadline(key, leaseExpireAt));
            mutableStats(taskId).inflightCount++;
            claimed.add(new ClaimedTaskWork(
                    taskId,
                    item.messageId(),
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
        if (ready.isEmpty()) {
            readyByTask.remove(taskId);
        }
        claimedItems.addAndGet(claimed.size());
        return List.copyOf(claimed);
    }

    @Override
    public synchronized ResultApplyOutcome applyResult(TaskWorkResult result) {
        if (!running.get()) {
            return ResultApplyOutcome.failed(result, "work runtime is stopped");
        }
        if (result == null || isBlank(result.taskId()) || isBlank(result.messageId())) {
            return ResultApplyOutcome.invalid(result, "taskId and messageId must not be blank");
        }
        WorkKey key = new WorkKey(result.taskId(), result.messageId());
        ActiveLeaseRecord lease = leaseByKey.get(key);
        if (lease == null) {
            duplicateResultItems.incrementAndGet();
            return ResultApplyOutcome.noActiveLease(result, "no active lease for result");
        }
        if (!isBlank(result.leaseToken()) && !result.leaseToken().equals(lease.leaseToken())) {
            staleResultItems.incrementAndGet();
            return ResultApplyOutcome.staleLease(result, "result leaseToken does not match active lease");
        }

        removeLease(key, lease);
        MutableTaskStats taskStats = mutableStats(result.taskId());
        if (taskStats.inflightCount > 0) {
            taskStats.inflightCount--;
        }
        resultAppliedItems.incrementAndGet();

        if (result.success()) {
            workByKey.remove(key);
            taskStats.successCount++;
            return ResultApplyOutcome.success(result);
        }

        TaskWorkEnvelope item = workByKey.get(key);
        boolean canRetry = result.retryable()
                && item != null
                && item.retryCount() < item.maxRetryCount();
        if (canRetry) {
            TaskWorkEnvelope retry = item.withRetry(item.retryCount() + 1, clock.get());
            workByKey.put(key, retry);
            readyByTask.computeIfAbsent(result.taskId(), ignored -> new ArrayDeque<>()).addLast(key);
            readyItems++;
            taskStats.readyCount++;
            return ResultApplyOutcome.retryScheduled(result, "retry budget allows re-dispatch");
        }

        taskStats.failedCount++;
        workByKey.remove(key);
        return ResultApplyOutcome.failureFinalized(result, "retry budget exhausted or result is not retryable");
    }

    @Override
    public synchronized List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now) {
        if (!running.get() || limit <= 0) {
            return List.of();
        }
        Instant cutoff = now == null ? clock.get() : now;
        List<ActiveLeaseRecord> expired = new ArrayList<>(limit);
        while (!leaseExpiryIndex.isEmpty() && expired.size() < limit) {
            LeaseDeadline deadline = leaseExpiryIndex.peek();
            if (deadline.expireAt().isAfter(cutoff)) {
                break;
            }
            leaseExpiryIndex.poll();
            ActiveLeaseRecord lease = leaseByKey.get(deadline.key());
            if (lease == null || !deadline.expireAt().equals(lease.leaseExpireAt())) {
                continue;
            }
            expired.add(lease);
        }
        expiredLeaseItems.addAndGet(expired.size());
        return List.copyOf(expired);
    }

    @Override
    public synchronized Optional<ActiveLeaseRecord> getActiveLease(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(leaseByKey.get(new WorkKey(taskId, messageId)));
    }

    @Override
    public synchronized boolean hasReadyWork(String taskId) {
        if (isBlank(taskId)) {
            return false;
        }
        promoteDueDelayed(clock.get());
        MutableTaskStats stats = statsByTask.get(taskId);
        return stats != null && stats.readyCount > 0;
    }

    @Override
    public synchronized boolean hasActiveLeaseForWorker(String taskId, String workerId) {
        if (isBlank(taskId) || isBlank(workerId)) {
            return false;
        }
        Set<WorkKey> keys = activeByWorker.get(workerId);
        return keys != null && keys.stream().anyMatch(key -> taskId.equals(key.taskId()));
    }

    @Override
    public synchronized TaskWorkStats stats(String taskId) {
        promoteDueDelayed(clock.get());
        MutableTaskStats stats = statsByTask.get(taskId);
        return stats == null ? TaskWorkStats.EMPTY : stats.snapshot();
    }

    @Override
    public synchronized TaskWorkRuntimeStats stats() {
        promoteDueDelayed(clock.get());
        long oldestReadyAgeMillis = oldestReadyAgeMillis();
        return new TaskWorkRuntimeStats(
                readyItems,
                leaseByKey.size(),
                delayedItems,
                readyByTask.size(),
                maxQueuedItems,
                oldestReadyAgeMillis,
                enqueuedItems.get(),
                claimedItems.get(),
                resultAppliedItems.get(),
                backpressureRejectedItems.get(),
                duplicateResultItems.get(),
                staleResultItems.get(),
                expiredLeaseItems.get(),
                discardedItems.get(),
                shutdownClearedItems.get()
        );
    }

    @Override
    public synchronized long discardTask(String taskId) {
        if (isBlank(taskId)) {
            return 0L;
        }
        long discarded = 0L;

        ArrayDeque<WorkKey> ready = readyByTask.remove(taskId);
        if (ready != null) {
            long readyRemoved = ready.stream()
                    .filter(key -> workByKey.containsKey(key))
                    .count();
            readyItems = Math.max(0L, readyItems - readyRemoved);
        }

        long delayedRemoved = removeDelayedWork(taskId);
        delayedItems = Math.max(0L, delayedItems - delayedRemoved);
        leaseExpiryIndex.removeIf(deadline -> taskId.equals(deadline.key().taskId()));

        List<WorkKey> leasedKeys = leaseByKey.keySet().stream()
                .filter(key -> taskId.equals(key.taskId()))
                .toList();
        for (WorkKey key : leasedKeys) {
            ActiveLeaseRecord lease = leaseByKey.get(key);
            if (lease != null) {
                removeLease(key, lease);
            }
        }

        List<WorkKey> workKeys = workByKey.keySet().stream()
                .filter(key -> taskId.equals(key.taskId()))
                .toList();
        for (WorkKey key : workKeys) {
            workByKey.remove(key);
            discarded++;
        }

        statsByTask.remove(taskId);
        if (discarded > 0) {
            discardedItems.addAndGet(discarded);
        }
        return discarded;
    }

    @Override
    public synchronized void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        long cleared = readyItems + delayedItems + leaseByKey.size();
        readyByTask.clear();
        workByKey.clear();
        leaseByKey.clear();
        activeByWorker.clear();
        leaseExpiryIndex.clear();
        delayedRetryIndex.clear();
        statsByTask.clear();
        readyItems = 0;
        delayedItems = 0;
        shutdownClearedItems.addAndGet(cleared);
    }

    private void promoteDueDelayed(Instant now) {
        while (!delayedRetryIndex.isEmpty()) {
            DelayedWork delayed = delayedRetryIndex.peek();
            if (delayed.visibleAt().isAfter(now)) {
                break;
            }
            delayedRetryIndex.poll();
            TaskWorkEnvelope item = workByKey.get(delayed.key());
            if (item == null || leaseByKey.containsKey(delayed.key())) {
                continue;
            }
            readyByTask.computeIfAbsent(delayed.key().taskId(), ignored -> new ArrayDeque<>()).addLast(delayed.key());
            delayedItems = Math.max(0, delayedItems - 1);
            MutableTaskStats stats = mutableStats(delayed.key().taskId());
            if (stats.delayedCount > 0) {
                stats.delayedCount--;
            }
            readyItems++;
            stats.readyCount++;
        }
    }

    private long removeDelayedWork(String taskId) {
        long before = delayedRetryIndex.size();
        delayedRetryIndex.removeIf(delayed -> taskId.equals(delayed.key().taskId()));
        return before - delayedRetryIndex.size();
    }

    private void removeLease(WorkKey key, ActiveLeaseRecord lease) {
        leaseByKey.remove(key);
        Set<WorkKey> workerKeys = activeByWorker.get(lease.workerId());
        if (workerKeys != null) {
            workerKeys.remove(key);
            if (workerKeys.isEmpty()) {
                activeByWorker.remove(lease.workerId());
            }
        }
    }

    private MutableTaskStats mutableStats(String taskId) {
        return statsByTask.computeIfAbsent(taskId, ignored -> new MutableTaskStats());
    }

    private void decrementReady(String taskId) {
        readyItems = Math.max(0, readyItems - 1);
        MutableTaskStats taskStats = mutableStats(taskId);
        if (taskStats.readyCount > 0) {
            taskStats.readyCount--;
        }
    }

    private boolean hasCapacity(List<WorkerCapacity> capacities) {
        return capacities.stream().anyMatch(WorkerCapacity::hasCapacity);
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

    private long oldestReadyAgeMillis() {
        Instant now = clock.get();
        Instant oldest = null;
        for (ArrayDeque<WorkKey> queue : readyByTask.values()) {
            WorkKey key = queue.peekFirst();
            TaskWorkEnvelope item = key == null ? null : workByKey.get(key);
            if (item == null) {
                continue;
            }
            if (oldest == null || item.createdAt().isBefore(oldest)) {
                oldest = item.createdAt();
            }
        }
        return oldest == null ? 0L : Math.max(0L, Duration.between(oldest, now).toMillis());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record WorkKey(String taskId, String messageId) {
        private static WorkKey from(TaskWorkEnvelope item) {
            return new WorkKey(item.taskId(), item.messageId());
        }
    }

    private record LeaseDeadline(WorkKey key, Instant expireAt) {
    }

    private record DelayedWork(WorkKey key, Instant visibleAt) {
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

    private static final class MutableTaskStats {
        private long readyCount;
        private long inflightCount;
        private long delayedCount;
        private long successCount;
        private long failedCount;
        private long expiredCount;

        private TaskWorkStats snapshot() {
            return new TaskWorkStats(readyCount, inflightCount, delayedCount, successCount, failedCount, expiredCount);
        }
    }
}
