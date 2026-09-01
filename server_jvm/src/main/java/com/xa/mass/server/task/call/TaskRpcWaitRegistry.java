package com.xa.mass.server.task.call;

import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

@Component
public final class TaskRpcWaitRegistry {

    private final int maxWaiters;
    private final int maxPendingObservations;
    private final long initialProbeIntervalMillis;
    private final long normalProbeIntervalMillis;
    private final long longProbeIntervalMillis;
    private final DelayQueue<DueItem> dueItems = new DelayQueue<>();
    private final Map<ItemKey, ItemWaitGroup> groups = new LinkedHashMap<>();
    private int waiterCount;
    private int pendingObservationCount;
    private long nextGeneration;
    private boolean closed;

    public TaskRpcWaitRegistry(TaskRpcProperties properties) {
        this.maxWaiters = properties.maxWaiters();
        this.maxPendingObservations =
                properties.maxPendingObservations();
        this.initialProbeIntervalMillis =
                properties.initialProbeIntervalMillis();
        this.normalProbeIntervalMillis =
                properties.normalProbeIntervalMillis();
        this.longProbeIntervalMillis =
                properties.longProbeIntervalMillis();
    }

    public boolean tryRegister(
            String taskId,
            List<String> messageIds,
            Map<String, TaskItemResult> observedResults,
            DeferredResult<Map<String, TaskItemResultResponse>> deferred
    ) {
        List<String> orderedIds = List.copyOf(messageIds);
        var observed = new LinkedHashMap<String, TaskItemResult>();
        orderedIds.forEach(messageId -> {
            TaskItemResult result = observedResults.get(messageId);
            if (result != null) {
                observed.put(messageId, result);
            }
        });
        var pending = new LinkedHashSet<ItemKey>();
        orderedIds.forEach(messageId -> {
            if (!observed.containsKey(messageId)) {
                pending.add(new ItemKey(taskId, messageId));
            }
        });
        if (pending.isEmpty()) {
            deferred.setResult(TaskItemResultResponse.fromObservedResults(
                    orderedIds,
                    observed
            ));
            return true;
        }

        BatchWaiter waiter;
        synchronized (this) {
            if (closed) {
                return false;
            }
            if (waiterCount >= maxWaiters
                    || pending.size() > maxPendingObservations
                            - pendingObservationCount) {
                return false;
            }
            waiter = new BatchWaiter(
                    this,
                    orderedIds,
                    observed,
                    pending,
                    deferred,
                    System.nanoTime()
            );
            for (ItemKey key : pending) {
                ItemWaitGroup group = groups.computeIfAbsent(
                        key,
                        ItemWaitGroup::new
                );
                group.waiters.add(waiter);
                if (!group.scheduled && !group.inFlight) {
                    schedule(group, 0);
                }
            }
            waiterCount++;
            pendingObservationCount += pending.size();
        }
        deferred.onTimeout(waiter::completeNotObserved);
        deferred.onError(ignored -> waiter.cancel());
        deferred.onCompletion(waiter::cancel);
        return true;
    }

    public List<ProbeRequest> takeDueBatch(int maxItems)
            throws InterruptedException {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
        while (true) {
            var dueBatch = new ArrayList<DueItem>(maxItems);
            dueBatch.add(dueItems.take());
            if (maxItems > 1) {
                dueItems.drainTo(dueBatch, maxItems - 1);
            }
            List<ProbeRequest> requests = activateDueItems(dueBatch);
            if (!requests.isEmpty()) {
                return requests;
            }
        }
    }

    public void completeResult(
            String taskId,
            String messageId,
            TaskItemResult result
    ) {
        java.util.Objects.requireNonNull(result, "result");
        ItemKey key = new ItemKey(taskId, messageId);
        List<BatchWaiter> waiters;
        synchronized (this) {
            ItemWaitGroup group = groups.get(key);
            if (group == null) {
                return;
            }
            waiters = List.copyOf(group.waiters);
        }
        waiters.forEach(waiter -> waiter.completeResult(key, result));
    }

    public synchronized void finishProbe(
            String taskId,
            String messageId,
            long retryDelayMillis
    ) {
        ItemKey key = new ItemKey(taskId, messageId);
        ItemWaitGroup group = groups.get(key);
        if (group == null) {
            return;
        }
        group.inFlight = false;
        if (group.waiters.isEmpty()) {
            groups.remove(key);
        } else if (!group.scheduled) {
            schedule(
                    group,
                    retryDelayMillis > 0
                            ? retryDelayMillis
                            : intervalForWaiterAgeMillis(
                                    oldestWaiterAgeMillis(group)
                            )
            );
        }
    }

    public void shutdown() {
        List<BatchWaiter> waiters;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            waiters = groups.values().stream()
                    .flatMap(group -> group.waiters.stream())
                    .distinct()
                    .toList();
        }
        waiters.forEach(BatchWaiter::completeNotObserved);
        dueItems.clear();
    }

    long intervalForWaiterAgeMillis(long ageMillis) {
        if (ageMillis <= 1_000) {
            return initialProbeIntervalMillis;
        }
        if (ageMillis <= 5_000) {
            return normalProbeIntervalMillis;
        }
        return longProbeIntervalMillis;
    }

    synchronized int waiterCount() {
        return waiterCount;
    }

    synchronized int pendingObservationCount() {
        return pendingObservationCount;
    }

    private synchronized List<ProbeRequest> activateDueItems(
            List<DueItem> dueBatch
    ) {
        var requests = new ArrayList<ProbeRequest>(dueBatch.size());
        for (DueItem due : dueBatch) {
            ItemWaitGroup group = groups.get(due.key);
            if (group == null
                    || !group.scheduled
                    || group.generation != due.generation) {
                continue;
            }
            group.scheduled = false;
            if (group.waiters.isEmpty()) {
                groups.remove(group.key);
                continue;
            }
            group.inFlight = true;
            requests.add(new ProbeRequest(
                    group.key.taskId,
                    group.key.messageId
            ));
        }
        return List.copyOf(requests);
    }

    private synchronized void remove(
            BatchWaiter waiter,
            Set<ItemKey> keys,
            boolean releaseWaiter
    ) {
        int removedAssociations = 0;
        for (ItemKey key : keys) {
            ItemWaitGroup group = groups.get(key);
            if (group == null || !group.waiters.remove(waiter)) {
                continue;
            }
            removedAssociations++;
            if (group.waiters.isEmpty() && !group.inFlight) {
                groups.remove(group.key);
            }
        }
        pendingObservationCount -= removedAssociations;
        if (releaseWaiter) {
            waiterCount--;
        }
    }

    private void schedule(ItemWaitGroup group, long delayMillis) {
        group.scheduled = true;
        group.generation = ++nextGeneration;
        dueItems.offer(new DueItem(
                group.key,
                group.generation,
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(delayMillis)
        ));
    }

    private static long oldestWaiterAgeMillis(ItemWaitGroup group) {
        long oldest = group.waiters.stream()
                .mapToLong(waiter -> waiter.registeredAtNanos)
                .min()
                .orElse(System.nanoTime());
        return Math.max(
                0,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest)
        );
    }

    record ProbeRequest(
            String taskId,
            String messageId
    ) {
    }

    private static final class BatchWaiter {

        private final TaskRpcWaitRegistry registry;
        private final List<String> orderedMessageIds;
        private final Map<String, TaskItemResult> observedResults;
        private final Set<ItemKey> pending;
        private final DeferredResult<Map<String, TaskItemResultResponse>>
                deferred;
        private final long registeredAtNanos;
        private boolean completed;

        private BatchWaiter(
                TaskRpcWaitRegistry registry,
                List<String> orderedMessageIds,
                Map<String, TaskItemResult> observedResults,
                Set<ItemKey> pending,
                DeferredResult<Map<String, TaskItemResultResponse>> deferred,
                long registeredAtNanos
        ) {
            this.registry = registry;
            this.orderedMessageIds = orderedMessageIds;
            this.observedResults = observedResults;
            this.pending = pending;
            this.deferred = deferred;
            this.registeredAtNanos = registeredAtNanos;
        }

        private synchronized boolean completeResult(
                ItemKey key,
                TaskItemResult result
        ) {
            if (completed || !pending.remove(key)) {
                return false;
            }
            observedResults.put(key.messageId, result);
            boolean finished = pending.isEmpty();
            if (finished) {
                completed = true;
            }
            registry.remove(this, Set.of(key), finished);
            if (!finished) {
                return true;
            }
            return deferred.setResult(response());
        }

        private synchronized boolean completeNotObserved() {
            if (completed) {
                return false;
            }
            completed = true;
            Set<ItemKey> remaining = Set.copyOf(pending);
            pending.clear();
            registry.remove(this, remaining, true);
            return deferred.setResult(response());
        }

        private synchronized void cancel() {
            if (completed) {
                return;
            }
            completed = true;
            Set<ItemKey> remaining = Set.copyOf(pending);
            pending.clear();
            registry.remove(this, remaining, true);
        }

        private Map<String, TaskItemResultResponse> response() {
            return TaskItemResultResponse.fromObservedResults(
                    orderedMessageIds,
                    observedResults
            );
        }
    }

    private record ItemKey(
            String taskId,
            String messageId
    ) {
    }

    private static final class ItemWaitGroup {

        private final ItemKey key;
        private final Set<BatchWaiter> waiters = new LinkedHashSet<>();
        private long generation;
        private boolean scheduled;
        private boolean inFlight;

        private ItemWaitGroup(ItemKey key) {
            this.key = key;
        }
    }

    private static final class DueItem implements Delayed {

        private final ItemKey key;
        private final long generation;
        private final long dueAtNanos;

        private DueItem(
                ItemKey key,
                long generation,
                long dueAtNanos
        ) {
            this.key = key;
            this.generation = generation;
            this.dueAtNanos = dueAtNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(
                    dueAtNanos - System.nanoTime(),
                    TimeUnit.NANOSECONDS
            );
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(
                    dueAtNanos,
                    ((DueItem) other).dueAtNanos
            );
        }
    }
}
