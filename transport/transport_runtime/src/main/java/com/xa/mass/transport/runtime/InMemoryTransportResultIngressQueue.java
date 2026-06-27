package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process keyed result ingress queue.
 */
public final class InMemoryTransportResultIngressQueue implements TransportResultIngressQueue {

    public static final int DEFAULT_CAPACITY = 100_000;

    private final LinkedBlockingQueue<ResultIngressEntry> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryTransportResultIngressQueue() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryTransportResultIngressQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public boolean offer(String resultQueueKey, ResultIngressEntry entry) {
        requireDefaultQueue(resultQueueKey);
        if (entry == null || !running.get()) {
            return false;
        }
        return queue.offer(entry);
    }

    @Override
    public ResultIngressEntry poll(String resultQueueKey, long timeoutMillis) throws InterruptedException {
        requireDefaultQueue(resultQueueKey);
        if (!running.get() && queue.isEmpty()) {
            return null;
        }
        if (timeoutMillis <= 0L) {
            return queue.poll();
        }
        return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        running.set(false);
    }

    private static void requireDefaultQueue(String resultQueueKey) {
        String normalized = Objects.requireNonNull(resultQueueKey, "resultQueueKey").trim();
        if (!TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported resultQueueKey: " + resultQueueKey);
        }
    }
}
