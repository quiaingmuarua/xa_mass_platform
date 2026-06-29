package com.xa.mass.transport.runtime;

import com.xa.mass.runtime.queue.InMemoryKeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.transport.channel.ResultIngressEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process keyed result ingress queue.
 */
public final class InMemoryTransportResultIngressQueue implements TransportResultIngressQueue {

    public static final int DEFAULT_CAPACITY = 100_000;
    private static final Logger logger = LoggerFactory.getLogger(InMemoryTransportResultIngressQueue.class);

    private final InMemoryKeyedBlockingQueueStore readyQueue;
    private final int capacity;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ResultIngressEntryCodec codec = new ResultIngressEntryCodec();

    public InMemoryTransportResultIngressQueue() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryTransportResultIngressQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.readyQueue = new InMemoryKeyedBlockingQueueStore(capacity);
    }

    @Override
    public boolean offer(String resultQueueKey, ResultIngressEntry entry) {
        requireDefaultQueue(resultQueueKey);
        if (entry == null || !running.get()) {
            return false;
        }
        KeyedQueueOfferResult result = readyQueue.offer(
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                new KeyedQueueEntry(codec.encode(entry), entry.message().createdAtEpochMillis()),
                capacity
        );
        return result.status() == KeyedQueueOfferResult.Status.ENQUEUED;
    }

    @Override
    public ResultIngressEntry poll(String resultQueueKey, long timeoutMillis) throws InterruptedException {
        requireDefaultQueue(resultQueueKey);
        if (!running.get()) {
            return null;
        }
        KeyedQueuePollResult result = readyQueue.poll(
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                1,
                Math.max(0L, timeoutMillis),
                TimeUnit.MILLISECONDS
        );
        if (result.items().isEmpty()) {
            return null;
        }
        return decodeFirst(result.items());
    }

    public void shutdown() {
        running.set(false);
        readyQueue.shutdown();
    }

    void pushRawReadyValueForTest(String value) {
        readyQueue.offer(
                TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY,
                new KeyedQueueEntry(value, System.currentTimeMillis()),
                capacity
        );
    }

    private ResultIngressEntry decodeFirst(List<KeyedQueueEntry> entries) {
        for (KeyedQueueEntry entry : entries) {
            try {
                return codec.decode(entry.value());
            } catch (RuntimeException ex) {
                logger.warn("Discarding invalid in-memory result ingress queue payload", ex);
            }
        }
        return null;
    }

    private static void requireDefaultQueue(String resultQueueKey) {
        String normalized = Objects.requireNonNull(resultQueueKey, "resultQueueKey").trim();
        if (!TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported resultQueueKey: " + resultQueueKey);
        }
    }
}
