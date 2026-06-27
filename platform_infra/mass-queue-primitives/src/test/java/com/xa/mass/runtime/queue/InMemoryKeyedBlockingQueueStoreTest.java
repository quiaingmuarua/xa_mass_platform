package com.xa.mass.runtime.queue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryKeyedBlockingQueueStoreTest {

    @Test
    void preservesFifoOrderPerKey() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry("a", 1L), 10);
        store.offer("k1", new KeyedQueueEntry("b", 2L), 10);

        assertEquals(List.of("a", "b"), store.drain("k1", 10).stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void isolatesKeys() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry("a", 1L), 10);
        store.offer("k2", new KeyedQueueEntry("b", 2L), 10);

        assertEquals(List.of("a"), store.drain("k1", 10).stream().map(KeyedQueueEntry::value).toList());
        assertEquals(List.of("b"), store.drain("k2", 10).stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void blocksAndWakesPoller() throws Exception {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        CompletableFuture<KeyedQueuePollResult> future = new CompletableFuture<>();
        Thread poller = new Thread(() -> {
            try {
                future.complete(store.poll("k1", 10, 1000, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            }
        }, "keyed-queue-poller");
        poller.setDaemon(true);
        poller.start();

        Thread.sleep(50L);
        store.offer("k1", new KeyedQueueEntry("a", 1L), 10);

        KeyedQueuePollResult result = future.get(2, TimeUnit.SECONDS);
        assertEquals(KeyedQueuePollStatus.DELIVERED, result.status());
        assertEquals(List.of("a"), result.items().stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void pollTimesOutWhenNoEntryArrives() throws Exception {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        KeyedQueuePollResult result = store.poll("k1", 10, 50, TimeUnit.MILLISECONDS);
        assertEquals(KeyedQueuePollStatus.EMPTY, result.status());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void concurrentOfferAndPollTimeoutDoesNotLoseQueuedEntries() throws Exception {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10_000, new SequenceClock());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 500; i++) {
                Future<KeyedQueuePollResult> poll = executor.submit(
                        () -> store.poll("k1", 1, 1, TimeUnit.MILLISECONDS));
                KeyedQueueOfferResult offer = store.offer("k1", new KeyedQueueEntry("v" + i, i), 10_000);
                assertEquals(KeyedQueueOfferResult.Status.ENQUEUED, offer.status());

                KeyedQueuePollResult polled = poll.get(1, TimeUnit.SECONDS);
                if (polled.status() == KeyedQueuePollStatus.DELIVERED) {
                    assertEquals(1, polled.items().size());
                } else {
                    List<KeyedQueueEntry> drained = store.drain("k1", 1);
                    assertEquals(1, drained.size());
                }
            }
            assertEquals(0, store.size("k1"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsPerKeyBackpressure() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        assertEquals(KeyedQueueOfferResult.Status.ENQUEUED,
                store.offer("k1", new KeyedQueueEntry("a", 1L), 1).status());
        assertEquals(KeyedQueueOfferResult.Status.BACKPRESSURE_REJECTED,
                store.offer("k1", new KeyedQueueEntry("b", 2L), 1).status());
    }

    @Test
    void capacityIsScopedPerKey() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(1, new SequenceClock());

        assertEquals(KeyedQueueOfferResult.Status.ENQUEUED,
                store.offer("k1", new KeyedQueueEntry("a", 1L), 10).status());
        assertEquals(KeyedQueueOfferResult.Status.ENQUEUED,
                store.offer("k2", new KeyedQueueEntry("b", 2L), 10).status());
    }

    @Test
    void sizeIsTargetedPointRead() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry("a", 1L), 10);
        store.offer("k1", new KeyedQueueEntry("b", 2L), 10);
        store.offer("k2", new KeyedQueueEntry("c", 3L), 10);

        assertEquals(2, store.size("k1"));
        assertEquals(1, store.size("k2"));
        assertEquals(0, store.size("missing"));
    }

    @Test
    void shutdownClearsStateAndRejectsFurtherOffers() {
        InMemoryKeyedBlockingQueueStore store = new InMemoryKeyedBlockingQueueStore(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry("a", 1L), 10);
        store.shutdown();

        assertEquals(0, store.size("k1"));
        assertEquals(KeyedQueueOfferResult.Status.UNAVAILABLE,
                store.offer("k1", new KeyedQueueEntry("b", 2L), 10).status());
    }

    private static final class SequenceClock implements java.util.function.LongSupplier {
        private long current;

        @Override
        public long getAsLong() {
            return current;
        }
    }
}
