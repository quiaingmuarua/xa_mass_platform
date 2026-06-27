package com.xa.mass.runtime.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * In-memory String keyed blocking queue.
 */
public final class InMemoryKeyedBlockingQueueStore implements KeyedBlockingQueueStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;

    private final ConcurrentMap<String, QueueState> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int maxItemsPerKeyLimit;
    @SuppressWarnings("unused")
    private final LongSupplier currentTimeMillis;

    public InMemoryKeyedBlockingQueueStore() {
        this(DEFAULT_MAX_QUEUED_ITEMS);
    }

    public InMemoryKeyedBlockingQueueStore(int maxItemsPerKeyLimit) {
        this(maxItemsPerKeyLimit, System::currentTimeMillis);
    }

    public InMemoryKeyedBlockingQueueStore(int maxItemsPerKeyLimit, LongSupplier currentTimeMillis) {
        if (maxItemsPerKeyLimit <= 0) {
            throw new IllegalArgumentException("maxItemsPerKeyLimit must be greater than 0");
        }
        this.maxItemsPerKeyLimit = maxItemsPerKeyLimit;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public KeyedQueueOfferResult offer(String key, KeyedQueueEntry entry, int maxItemsPerKey) {
        if (isBlank(key) || entry == null) {
            return KeyedQueueOfferResult.invalid("key and entry must not be null");
        }
        if (maxItemsPerKey <= 0) {
            return KeyedQueueOfferResult.backpressureRejected("queue capacity is exhausted");
        }
        if (!running.get()) {
            return KeyedQueueOfferResult.unavailable("queue store is stopped");
        }
        String normalizedKey = key.trim();
        while (true) {
            QueueState queue = queueState(normalizedKey);
            synchronized (queue) {
                if (queues.get(normalizedKey) != queue) {
                    continue;
                }
                if (!running.get()) {
                    cleanupIfEmpty(normalizedKey, queue);
                    return KeyedQueueOfferResult.unavailable("queue store is stopped");
                }
                int effectiveMaxItems = Math.min(maxItemsPerKey, maxItemsPerKeyLimit);
                if (queue.items.size() >= effectiveMaxItems) {
                    return KeyedQueueOfferResult.backpressureRejected("queue is full");
                }
                queue.items.addLast(entry);
                if (queue.waiters > 0) {
                    queue.notify();
                }
                return KeyedQueueOfferResult.enqueued();
            }
        }
    }

    @Override
    public List<KeyedQueueEntry> drain(String key, int maxItems) {
        if (isBlank(key) || maxItems <= 0 || !running.get()) {
            return List.of();
        }
        String normalizedKey = key.trim();
        QueueState queue = queues.get(normalizedKey);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            List<KeyedQueueEntry> drained = drainLocked(queue, maxItems);
            cleanupIfEmpty(normalizedKey, queue);
            return drained.isEmpty() ? List.of() : List.copyOf(drained);
        }
    }

    @Override
    public KeyedQueuePollResult poll(String key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException {
        if (isBlank(key) || maxItems <= 0) {
            return KeyedQueuePollResult.invalid();
        }
        if (!running.get()) {
            return KeyedQueuePollResult.shutdown();
        }
        if (timeout <= 0) {
            List<KeyedQueueEntry> drained = drain(key, maxItems);
            return drained.isEmpty() ? KeyedQueuePollResult.empty() : KeyedQueuePollResult.delivered(drained);
        }
        String normalizedKey = key.trim();
        long timeoutMillis = Math.max(1L, unit == null ? timeout : unit.toMillis(timeout));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        QueueState queue = queueState(normalizedKey);
        synchronized (queue) {
            queue.waiters++;
            try {
                while (queue.items.isEmpty() && running.get()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return KeyedQueuePollResult.empty();
                    }
                    queue.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
                if (queue.items.isEmpty()) {
                    return running.get() ? KeyedQueuePollResult.empty() : KeyedQueuePollResult.shutdown();
                }
                List<KeyedQueueEntry> drained = drainLocked(queue, maxItems);
                return KeyedQueuePollResult.delivered(drained);
            } finally {
                queue.waiters--;
                cleanupIfEmpty(normalizedKey, queue);
            }
        }
    }

    @Override
    public int size(String key) {
        if (isBlank(key)) {
            return 0;
        }
        QueueState queue = queues.get(key.trim());
        if (queue == null) {
            return 0;
        }
        synchronized (queue) {
            return queue.items.size();
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (QueueState queue : queues.values()) {
            synchronized (queue) {
                queue.items.clear();
                queue.notifyAll();
            }
        }
        queues.clear();
    }

    private QueueState queueState(String key) {
        return queues.computeIfAbsent(key, ignored -> new QueueState());
    }

    private void cleanupIfEmpty(String key, QueueState queue) {
        if (queue.items.isEmpty() && queue.waiters == 0) {
            queues.remove(key, queue);
        }
    }

    private static List<KeyedQueueEntry> drainLocked(QueueState queue, int maxItems) {
        List<KeyedQueueEntry> drained = new ArrayList<>(Math.max(1, maxItems));
        while (drained.size() < maxItems) {
            KeyedQueueEntry entry = queue.items.pollFirst();
            if (entry == null) {
                break;
            }
            drained.add(entry);
        }
        return drained;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class QueueState {
        private final Deque<KeyedQueueEntry> items = new ArrayDeque<>();
        private int waiters;
    }
}
