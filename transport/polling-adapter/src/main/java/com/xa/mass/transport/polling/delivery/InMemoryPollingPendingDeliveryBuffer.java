package com.xa.mass.transport.polling.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDeliveryAddressing;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * In-memory polling pending-delivery buffer.
 *
 * <p>Storage is mailbox-scoped and worker-slotted:
 * {@code adapterMailboxKey -> authenticatedWorkerId -> queue}. Polling a worker
 * never scans or destructively pops another worker's slot.
 */
public final class InMemoryPollingPendingDeliveryBuffer implements PollingPendingDeliveryBuffer {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    public static final int DEFAULT_MAX_ITEMS_PER_WORKER = 10_000;
    private static final long SNAPSHOT_CACHE_WINDOW_MILLIS = 250L;

    private final ConcurrentMap<String, MailboxState> mailboxes = new ConcurrentHashMap<>();
    private final AtomicInteger queuedItems = new AtomicInteger();
    private final AtomicLong enqueuedItems = new AtomicLong();
    private final AtomicLong drainedItems = new AtomicLong();
    private final AtomicLong backpressureRejectedItems = new AtomicLong();
    private final AtomicLong invalidItems = new AtomicLong();
    private final AtomicLong unavailableItems = new AtomicLong();
    private final AtomicLong shutdownClearedItems = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> backpressureRejectedItemsByMailbox = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object snapshotLock = new Object();
    private final int maxQueuedItems;
    private final int maxItemsPerWorker;
    private final LongSupplier currentTimeMillis;
    private volatile PollingPendingDeliveryBufferStats cachedSnapshot;
    private volatile long cachedSnapshotAtMillis;

    public InMemoryPollingPendingDeliveryBuffer() {
        this(DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_WORKER);
    }

    public InMemoryPollingPendingDeliveryBuffer(int maxQueuedItems) {
        this(maxQueuedItems, DEFAULT_MAX_ITEMS_PER_WORKER, System::currentTimeMillis);
    }

    public InMemoryPollingPendingDeliveryBuffer(int maxQueuedItems, int maxItemsPerWorker) {
        this(maxQueuedItems, maxItemsPerWorker, System::currentTimeMillis);
    }

    InMemoryPollingPendingDeliveryBuffer(int maxQueuedItems,
                                         int maxItemsPerWorker,
                                         LongSupplier currentTimeMillis) {
        if (maxQueuedItems <= 0) {
            throw new IllegalArgumentException("maxQueuedItems must be greater than 0");
        }
        if (maxItemsPerWorker <= 0) {
            throw new IllegalArgumentException("maxItemsPerWorker must be positive");
        }
        this.maxQueuedItems = maxQueuedItems;
        this.maxItemsPerWorker = maxItemsPerWorker;
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    @Override
    public List<DispatchOutcome> enqueue(String adapterMailboxKey, List<DispatchRoutingItem> items) {
        String normalizedMailboxKey = normalizeText(adapterMailboxKey);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (DispatchRoutingItem item : items) {
            outcomes.add(enqueueOne(normalizedMailboxKey, item));
        }
        return Collections.unmodifiableList(outcomes);
    }

    @Override
    public PollingPendingDeliveryPollResult poll(String adapterMailboxKey,
                                                 String authenticatedWorkerId,
                                                 int maxItems,
                                                 long timeoutMillis) throws InterruptedException {
        String normalizedMailboxKey = normalizeText(adapterMailboxKey);
        String normalizedWorkerId = normalizeText(authenticatedWorkerId);
        if (normalizedMailboxKey == null || normalizedWorkerId == null || maxItems <= 0) {
            return PollingPendingDeliveryPollResult.invalidRequest();
        }
        if (!running.get()) {
            return PollingPendingDeliveryPollResult.shutdown();
        }
        if (timeoutMillis <= 0) {
            List<DispatchRoutingItem> drained = drain(normalizedMailboxKey, normalizedWorkerId, maxItems);
            return drained.isEmpty()
                    ? PollingPendingDeliveryPollResult.empty()
                    : PollingPendingDeliveryPollResult.deliveredView(drained);
        }

        WorkerSlot slot = workerSlot(normalizedMailboxKey, normalizedWorkerId);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        synchronized (slot) {
            slot.waiters++;
            invalidateSnapshot();
            try {
                while (running.get() && slot.items.isEmpty()) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return PollingPendingDeliveryPollResult.empty();
                    }
                    slot.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                }
                if (!running.get()) {
                    return PollingPendingDeliveryPollResult.shutdown();
                }
                List<DispatchRoutingItem> drained = drainLocked(slot, maxItems);
                if (drained.isEmpty()) {
                    return PollingPendingDeliveryPollResult.empty();
                }
                releaseGlobalSlots(drained.size());
                drainedItems.addAndGet(drained.size());
                invalidateSnapshot();
                return PollingPendingDeliveryPollResult.deliveredView(Collections.unmodifiableList(drained));
            } finally {
                slot.waiters--;
                invalidateSnapshot();
                cleanupIfEmpty(normalizedMailboxKey, normalizedWorkerId, slot);
            }
        }
    }

    public PollingPendingDeliveryBufferStats stats() {
        PollingPendingDeliveryBufferStats snapshot = cachedSnapshot;
        long now = currentTimeMillis.getAsLong();
        if (snapshot != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
            return snapshot;
        }
        synchronized (snapshotLock) {
            snapshot = cachedSnapshot;
            now = currentTimeMillis.getAsLong();
            if (snapshot != null && now - cachedSnapshotAtMillis <= SNAPSHOT_CACHE_WINDOW_MILLIS) {
                return snapshot;
            }
            PollingPendingDeliveryBufferStats refreshed = snapshot(now);
            cachedSnapshot = refreshed;
            cachedSnapshotAtMillis = now;
            return refreshed;
        }
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        int cleared = queuedItems.get();
        for (MailboxState mailbox : mailboxes.values()) {
            for (WorkerSlot slot : mailbox.slots.values()) {
                synchronized (slot) {
                    slot.items.clear();
                    slot.notifyAll();
                }
            }
        }
        mailboxes.clear();
        queuedItems.set(0);
        shutdownClearedItems.addAndGet(cleared);
        invalidateSnapshot();
    }

    private DispatchOutcome enqueueOne(String normalizedMailboxKey, DispatchRoutingItem item) {
        String normalizedWorkerId = item == null ? null : normalizeText(item.selectedWorkerId());
        if (item == null || normalizedMailboxKey == null) {
            invalidItems.incrementAndGet();
            return DispatchOutcome.invalid(
                    item != null ? item.deliveryId() : null,
                    normalizedWorkerId,
                    item != null ? item.correlationRef() : null,
                    "adapterMailboxKey and item must not be blank"
            );
        }
        if (normalizedWorkerId == null) {
            invalidItems.incrementAndGet();
            return DispatchOutcome.invalid(item.deliveryId(), null, item.correlationRef(),
                    "selectedWorkerId must not be blank");
        }
        if (!running.get()) {
            unavailableItems.incrementAndGet();
            return unavailable(item.deliveryId(), normalizedWorkerId, item.correlationRef(),
                    "polling pending delivery buffer is stopped");
        }

        DispatchRoutingItem normalizedItem = normalizeItem(item, normalizedWorkerId);
        WorkerSlot slot = workerSlot(normalizedMailboxKey, normalizedWorkerId);
        synchronized (slot) {
            if (!running.get()) {
                cleanupIfEmpty(normalizedMailboxKey, normalizedWorkerId, slot);
                unavailableItems.incrementAndGet();
                return unavailable(normalizedItem.deliveryId(), normalizedWorkerId, normalizedItem.correlationRef(),
                        "polling pending delivery buffer is stopped");
            }
            if (slot.items.size() >= maxItemsPerWorker) {
                rejectBackpressure(normalizedMailboxKey);
                return DispatchOutcome.backpressure(
                        normalizedItem.deliveryId(),
                        normalizedWorkerId,
                        normalizedItem.correlationRef(),
                        "polling worker pending delivery buffer is full"
                );
            }
            if (!reserveGlobalSlot()) {
                cleanupIfEmpty(normalizedMailboxKey, normalizedWorkerId, slot);
                rejectBackpressure(normalizedMailboxKey);
                return DispatchOutcome.backpressure(
                        normalizedItem.deliveryId(),
                        normalizedWorkerId,
                        normalizedItem.correlationRef(),
                        "polling pending delivery backlog is full"
                );
            }
            slot.items.addLast(normalizedItem);
            enqueuedItems.incrementAndGet();
            invalidateSnapshot();
            slot.notify();
            return DispatchOutcome.queued(
                    normalizedItem.deliveryId(),
                    normalizedWorkerId,
                    normalizedItem.correlationRef()
            );
        }
    }

    private List<DispatchRoutingItem> drain(String normalizedMailboxKey, String normalizedWorkerId, int maxItems) {
        if (!running.get()) {
            return List.of();
        }
        MailboxState mailbox = mailboxes.get(normalizedMailboxKey);
        if (mailbox == null) {
            return List.of();
        }
        WorkerSlot slot = mailbox.slots.get(normalizedWorkerId);
        if (slot == null) {
            return List.of();
        }
        synchronized (slot) {
            List<DispatchRoutingItem> drained = drainLocked(slot, maxItems);
            if (!drained.isEmpty()) {
                releaseGlobalSlots(drained.size());
                drainedItems.addAndGet(drained.size());
                invalidateSnapshot();
            }
            cleanupIfEmpty(normalizedMailboxKey, normalizedWorkerId, slot);
            return drained.isEmpty() ? List.of() : Collections.unmodifiableList(drained);
        }
    }

    private PollingPendingDeliveryBufferStats snapshot(long nowMillis) {
        int queueCount = 0;
        int waiters = 0;
        long oldestCreatedAt = Long.MAX_VALUE;
        Map<String, PollingPendingDeliveryQueueStats> queueByMailbox = new LinkedHashMap<>();
        for (Map.Entry<String, MailboxState> mailboxEntry : mailboxes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList()) {
            int mailboxQueuedItems = 0;
            int mailboxQueueCount = 0;
            int mailboxWaiters = 0;
            long mailboxOldestCreatedAt = Long.MAX_VALUE;
            for (WorkerSlot slot : mailboxEntry.getValue().slots.values()) {
                synchronized (slot) {
                    if (slot.items.isEmpty() && slot.waiters == 0) {
                        continue;
                    }
                    if (!slot.items.isEmpty()) {
                        mailboxQueueCount++;
                        mailboxQueuedItems += slot.items.size();
                        mailboxOldestCreatedAt = Math.min(mailboxOldestCreatedAt, oldestCreatedAt(slot));
                    }
                    mailboxWaiters += slot.waiters;
                }
            }
            if (mailboxQueuedItems == 0 && mailboxWaiters == 0) {
                continue;
            }
            queueCount += mailboxQueueCount;
            waiters += mailboxWaiters;
            oldestCreatedAt = Math.min(oldestCreatedAt, mailboxOldestCreatedAt);
            long oldestAge = mailboxOldestCreatedAt == Long.MAX_VALUE
                    ? 0L
                    : Math.max(0L, nowMillis - mailboxOldestCreatedAt);
            queueByMailbox.put(mailboxEntry.getKey(), new PollingPendingDeliveryQueueStats(
                    mailboxQueuedItems,
                    mailboxQueueCount,
                    mailboxWaiters,
                    oldestAge,
                    backpressureRejectedItemsByMailbox
                            .getOrDefault(mailboxEntry.getKey(), new AtomicLong())
                            .get()
            ));
        }
        backpressureRejectedItemsByMailbox.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> queueByMailbox.computeIfAbsent(entry.getKey(), ignored ->
                        new PollingPendingDeliveryQueueStats(0, 0, 0, 0L, entry.getValue().get())));

        long oldestQueuedAgeMillis = oldestCreatedAt == Long.MAX_VALUE
                ? 0L
                : Math.max(0L, nowMillis - oldestCreatedAt);
        return new PollingPendingDeliveryBufferStats(
                queuedItems.get(),
                queueCount,
                waiters,
                maxQueuedItems,
                oldestQueuedAgeMillis,
                enqueuedItems.get(),
                drainedItems.get(),
                backpressureRejectedItems.get(),
                invalidItems.get(),
                unavailableItems.get(),
                shutdownClearedItems.get(),
                queueByMailbox.isEmpty() ? Map.of() : Map.copyOf(queueByMailbox)
        );
    }

    private WorkerSlot workerSlot(String normalizedMailboxKey, String normalizedWorkerId) {
        return mailboxes
                .computeIfAbsent(normalizedMailboxKey, ignored -> new MailboxState())
                .slots
                .computeIfAbsent(normalizedWorkerId, ignored -> new WorkerSlot());
    }

    private void cleanupIfEmpty(String normalizedMailboxKey, String normalizedWorkerId, WorkerSlot slot) {
        if (!slot.items.isEmpty() || slot.waiters > 0) {
            return;
        }
        MailboxState mailbox = mailboxes.get(normalizedMailboxKey);
        if (mailbox == null) {
            return;
        }
        mailbox.slots.remove(normalizedWorkerId, slot);
        if (mailbox.slots.isEmpty()) {
            mailboxes.remove(normalizedMailboxKey, mailbox);
        }
        invalidateSnapshot();
    }

    private static List<DispatchRoutingItem> drainLocked(WorkerSlot slot, int maxItems) {
        List<DispatchRoutingItem> drained = new ArrayList<>(Math.max(1, maxItems));
        while (!slot.items.isEmpty() && drained.size() < maxItems) {
            drained.add(slot.items.removeFirst());
        }
        return drained;
    }

    private static long oldestCreatedAt(WorkerSlot slot) {
        long oldest = Long.MAX_VALUE;
        for (DispatchRoutingItem item : slot.items) {
            oldest = Math.min(oldest, item.createdAtEpochMillis());
        }
        return oldest == Long.MAX_VALUE ? 0L : oldest;
    }

    private boolean reserveGlobalSlot() {
        while (true) {
            int current = queuedItems.get();
            if (current >= maxQueuedItems) {
                return false;
            }
            if (queuedItems.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseGlobalSlots(int count) {
        if (count <= 0) {
            return;
        }
        queuedItems.updateAndGet(current -> Math.max(0, current - count));
    }

    private void rejectBackpressure(String normalizedMailboxKey) {
        backpressureRejectedItems.incrementAndGet();
        backpressureRejectedItemsByMailbox
                .computeIfAbsent(normalizedMailboxKey, ignored -> new AtomicLong())
                .incrementAndGet();
        invalidateSnapshot();
    }

    private void invalidateSnapshot() {
        cachedSnapshot = null;
        cachedSnapshotAtMillis = 0L;
    }

    private static DispatchOutcome unavailable(String deliveryId,
                                               String selectedWorkerId,
                                               String correlationRef,
                                               String reason) {
        return new DispatchOutcome(
                deliveryId,
                selectedWorkerId,
                correlationRef,
                DispatchOutcomeStatus.UNAVAILABLE,
                true,
                reason,
                System.currentTimeMillis()
        );
    }

    private static DispatchRoutingItem normalizeItem(DispatchRoutingItem item, String normalizedSelectedWorkerId) {
        if (Objects.equals(normalizedSelectedWorkerId, item.selectedWorkerId())) {
            return item;
        }
        return new DispatchRoutingItem(
                item.deliveryId(),
                normalizedSelectedWorkerId,
                item.payload(),
                item.correlationRef(),
                item.deadlineEpochMillis(),
                item.createdAtEpochMillis()
        );
    }

    private static String normalizeText(String value) {
        return TransportDeliveryAddressing.normalizeText(value);
    }

    private static final class MailboxState {
        private final ConcurrentMap<String, WorkerSlot> slots = new ConcurrentHashMap<>();
    }

    private static final class WorkerSlot {
        private final ArrayDeque<DispatchRoutingItem> items = new ArrayDeque<>();
        private int waiters;
    }
}
