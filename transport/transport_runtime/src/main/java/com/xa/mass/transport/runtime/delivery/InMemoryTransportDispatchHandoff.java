package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.InMemoryKeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueuePollStatus;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process non-blocking dispatch handoff.
 */
public final class InMemoryTransportDispatchHandoff implements TransportDispatchHandoff {

    private final InMemoryKeyedBlockingQueueStore readyQueue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int capacity;
    private final TransportDispatchBatchCodec codec = new TransportDispatchBatchCodec();

    public InMemoryTransportDispatchHandoff(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.readyQueue = new InMemoryKeyedBlockingQueueStore(capacity);
    }

    @Override
    public List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items) {
        String queueKey = normalizeRequired(dispatchQueueKey, "dispatchQueueKey");
        List<DispatchMessage> itemsToOffer = normalizeItems(items);
        if (!running.get()) {
            return itemsToOffer.stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "dispatch handoff is stopped"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(itemsToOffer.size());
        for (DispatchMessage item : itemsToOffer) {
            boolean accepted = offerReadyItem(queueKey, item);
            outcomes.add(DispatchOutcomeFactory.fromItem(
                    item,
                    accepted ? DispatchOutcomeStatus.QUEUED : DispatchOutcomeStatus.BACKPRESSURE,
                    !accepted,
                    accepted ? null : "dispatch handoff queue is full"
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public List<DispatchMessage> poll(String adapterMailboxKey,
                                          int maxItems,
                                          long timeoutMillis) throws InterruptedException {
        String mailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be greater than 0");
        }
        if (!running.get() && readyQueueEmpty(mailboxKey)) {
            return List.of();
        }
        return pollLocalMailboxItems(mailboxKey, maxItems, Math.max(0L, timeoutMillis));
    }

    @Override
    public void shutdown() {
        running.set(false);
        readyQueue.shutdown();
    }

    private boolean offerReadyItem(String adapterMailboxKey, DispatchMessage item) {
        KeyedQueueOfferResult result = readyQueue.offer(
                adapterMailboxKey,
                new KeyedQueueEntry(codec.encodeItem(item), item.createdAtEpochMillis()),
                capacity
        );
        return result.status() == KeyedQueueOfferResult.Status.ENQUEUED;
    }

    private List<DispatchMessage> pollLocalMailboxItems(String adapterMailboxKey,
                                                           int maxItems,
                                                           long timeoutMillis) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<DispatchMessage> items = new ArrayList<>(maxItems);
        do {
            KeyedQueuePollResult result = readyQueue.poll(
                    adapterMailboxKey,
                    maxItems,
                    0L,
                    TimeUnit.MILLISECONDS
            );
            if (result.status() == KeyedQueuePollStatus.DELIVERED) {
                for (KeyedQueueEntry entry : result.items()) {
                    try {
                        items.add(codec.decodeItem(entry.value()));
                    } catch (RuntimeException ignored) {
                        // Corrupt handoff entries are dropped as store-local corruption.
                    }
                }
                return List.copyOf(items);
            }
            if (timeoutMillis <= 0L) {
                return List.of();
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return List.of();
            }
            TimeUnit.MILLISECONDS.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 50L));
            timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
        } while (running.get());
        return List.of();
    }

    private boolean readyQueueEmpty(String adapterMailboxKey) {
        return readyQueue.size(adapterMailboxKey) == 0;
    }

    private static List<DispatchMessage> normalizeItems(List<DispatchMessage> items) {
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        for (DispatchMessage item : items) {
            if (item == null) {
                throw new IllegalArgumentException("items must not contain null");
            }
        }
        return List.copyOf(items);
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
