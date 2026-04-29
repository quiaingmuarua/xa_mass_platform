package com.xa.mass.runtime.queue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryKeyedBlockingQueueStoreTest {

    @Test
    void preservesFifoOrderPerKey() {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry<>("a", 1L), 10);
        store.offer("k1", new KeyedQueueEntry<>("b", 2L), 10);

        assertEquals(List.of("a", "b"), store.drain("k1", 10).stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void isolatesKeys() {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry<>("a", 1L), 10);
        store.offer("k2", new KeyedQueueEntry<>("b", 2L), 10);

        assertEquals(List.of("a"), store.drain("k1", 10).stream().map(KeyedQueueEntry::value).toList());
        assertEquals(List.of("b"), store.drain("k2", 10).stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void blocksAndWakesPoller() throws Exception {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        CompletableFuture<List<KeyedQueueEntry<String>>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("k1", 10, 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(50L);
        store.offer("k1", new KeyedQueueEntry<>("a", 1L), 10);

        assertEquals(List.of("a"), future.get(2, TimeUnit.SECONDS).stream().map(KeyedQueueEntry::value).toList());
    }

    @Test
    void pollTimesOutWhenNoEntryArrives() throws Exception {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        assertTrue(store.poll("k1", 10, 50, TimeUnit.MILLISECONDS).isEmpty());
    }

    @Test
    void rejectsPerKeyBackpressure() {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        assertEquals(KeyedQueueOfferResult.Status.ENQUEUED,
                store.offer("k1", new KeyedQueueEntry<>("a", 1L), 1).status());
        assertEquals(KeyedQueueOfferResult.Status.BACKPRESSURE_REJECTED,
                store.offer("k1", new KeyedQueueEntry<>("b", 2L), 1).status());
    }

    @Test
    void rejectsGlobalBackpressure() {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(1, new SequenceClock());

        assertEquals(KeyedQueueOfferResult.Status.ENQUEUED,
                store.offer("k1", new KeyedQueueEntry<>("a", 1L), 10).status());
        assertEquals(KeyedQueueOfferResult.Status.BACKPRESSURE_REJECTED,
                store.offer("k2", new KeyedQueueEntry<>("b", 2L), 10).status());
    }

    @Test
    void shutdownClearsStateAndRejectsFurtherOffers() {
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, new SequenceClock());

        store.offer("k1", new KeyedQueueEntry<>("a", 1L), 10);
        store.shutdown();

        KeyedQueueSnapshot<String> snapshot = store.snapshot();
        assertEquals(0, snapshot.queuedItems());
        assertEquals(1L, snapshot.shutdownClearedItems());
        assertEquals(KeyedQueueOfferResult.Status.UNAVAILABLE,
                store.offer("k1", new KeyedQueueEntry<>("b", 2L), 10).status());
    }

    @Test
    void snapshotTracksCountersAndOldestAge() {
        SequenceClock clock = new SequenceClock();
        InMemoryKeyedBlockingQueueStore<String, String> store =
                new InMemoryKeyedBlockingQueueStore<>(10, clock);

        store.offer("k1", new KeyedQueueEntry<>("a", 100L), 10);
        store.offer("k2", new KeyedQueueEntry<>("b", 200L), 10);
        clock.set(500L);

        KeyedQueueSnapshot<String> snapshot = store.snapshot();
        assertEquals(2, snapshot.queuedItems());
        assertEquals(2, snapshot.queueCount());
        assertEquals(400L, snapshot.oldestQueuedAgeMillis());
        assertEquals(2L, snapshot.enqueuedItems());
    }

    private static final class SequenceClock implements java.util.function.LongSupplier {
        private long current;

        @Override
        public long getAsLong() {
            return current;
        }

        private void set(long value) {
            current = value;
        }
    }
}
