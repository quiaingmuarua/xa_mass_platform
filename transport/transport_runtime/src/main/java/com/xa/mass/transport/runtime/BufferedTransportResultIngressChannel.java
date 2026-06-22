package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.TransportResultIngressHandler;
import com.xa.mass.transport.channel.TransportResultIngressOutcome;
import com.xa.mass.transport.channel.ResultIngressEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Async buffer that decouples adapter-thread result delivery from engine-side
 * processing.
 */
public final class BufferedTransportResultIngressChannel implements TransportResultIngressChannel {

    private static final Logger logger = LoggerFactory.getLogger(BufferedTransportResultIngressChannel.class);

    static final int DEFAULT_CAPACITY = 2048;
    private static final long DRAIN_JOIN_MILLIS = 5_000L;
    private static final int MAX_DRAIN_BATCH = 64;

    private final TransportResultIngressHandler delegate;
    private final LinkedBlockingQueue<ResultIngressEntry> queue;
    private volatile boolean shutdown;
    private final Thread drainerThread;

    public BufferedTransportResultIngressChannel(TransportResultIngressHandler delegate) {
        this(delegate, DEFAULT_CAPACITY);
    }

    public BufferedTransportResultIngressChannel(TransportResultIngressHandler delegate, int capacity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.drainerThread = Thread.ofVirtual()
                .name("result-ingress-buffer-drainer")
                .start(this::drain);
    }

    @Override
    public boolean ingest(ResultIngressEntry entry) {
        if (entry == null || shutdown) {
            return false;
        }
        boolean offered = queue.offer(entry);
        if (!offered) {
            logger.warn("Result ingress buffer full ({} capacity); falling back to synchronous ingress resultMessageId={}",
                    queue.size(), entry.message().resultMessageId());
            return handle(entry).ackable();
        }
        return true;
    }

    public void shutdown() {
        shutdown = true;
        drainerThread.interrupt();
        try {
            drainerThread.join(DRAIN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (drainerThread.isAlive()) {
            logger.warn("Result ingress buffer drainer did not finish within {}ms; {} item(s) may be unprocessed",
                    DRAIN_JOIN_MILLIS, queue.size());
        }
    }

    private void drain() {
        List<ResultIngressEntry> batch = new ArrayList<>(MAX_DRAIN_BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ResultIngressEntry item = queue.poll(200, TimeUnit.MILLISECONDS);
                if (item != null) {
                    batch.clear();
                    batch.add(item);
                    queue.drainTo(batch, MAX_DRAIN_BATCH - 1);
                    processBatch(batch);
                } else if (shutdown) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        batch.clear();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
    }

    private void processBatch(List<ResultIngressEntry> batch) {
        for (ResultIngressEntry item : batch) {
            TransportResultIngressOutcome outcome = handle(item);
            if (!outcome.ackable() && !shutdown) {
                logger.warn("Result ingress delegate returned retryable outcome; requeueing resultMessageId={}",
                        item.message().resultMessageId());
                queue.offer(item);
            }
        }
    }

    private TransportResultIngressOutcome handle(ResultIngressEntry item) {
        try {
            TransportResultIngressOutcome outcome = delegate.handle(item);
            return outcome != null ? outcome : TransportResultIngressOutcome.RETRYABLE_FAILURE;
        } catch (RuntimeException ex) {
            logger.error("Result ingress delegate failed for resultMessageId={}", item.message().resultMessageId(), ex);
            return TransportResultIngressOutcome.RETRYABLE_FAILURE;
        }
    }
}
