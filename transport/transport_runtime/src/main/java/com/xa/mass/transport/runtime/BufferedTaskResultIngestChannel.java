package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Async buffer that decouples transport-thread result delivery from engine-side
 * processing. {@code ingest()} returns as soon as the report is enqueued; a
 * dedicated virtual-thread drainer forwards each item to the wrapped synchronous
 * channel.
 *
 * <p>Backpressure: the fast path is still a non-blocking queue offer. When the
 * queue is full, the current caller synchronously forwards the result to the
 * delegate instead of dropping an already-received worker result. This keeps
 * correctness ahead of adapter-thread isolation under sustained overload.
 *
 * <p>Shutdown: call {@link #shutdown()} before stopping the engine. The drainer
 * will process all remaining queued items before returning.
 */
public final class BufferedTaskResultIngestChannel implements TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(BufferedTaskResultIngestChannel.class);

    static final int DEFAULT_CAPACITY = 2048;
    private static final long DRAIN_JOIN_MILLIS = 5_000L;
    private static final int MAX_DRAIN_BATCH = 64;

    private final TaskResultIngestChannel delegate;
    private final LinkedBlockingQueue<Object> queue;
    private volatile boolean shutdown;
    private final Thread drainerThread;

    public BufferedTaskResultIngestChannel(TaskResultIngestChannel delegate) {
        this(delegate, DEFAULT_CAPACITY);
    }

    public BufferedTaskResultIngestChannel(TaskResultIngestChannel delegate, int capacity) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.drainerThread = Thread.ofVirtual()
                .name("result-ingest-buffer-drainer")
                .start(this::drain);
    }

    @Override
    public boolean ingest(TaskResultReport report) {
        if (report == null || shutdown) {
            return false;
        }
        boolean offered = queue.offer(report);
        if (!offered) {
            logger.warn("Result ingest buffer full ({} capacity); falling back to synchronous report ingest taskId={}, messageId={}",
                    queue.size(), report.getTaskId(), report.getMessageId());
            return delegate.ingest(report);
        }
        return offered;
    }

    @Override
    public boolean ingest(TransportResultEnvelope envelope) {
        if (envelope == null || shutdown) {
            return false;
        }
        boolean offered = queue.offer(envelope);
        if (!offered) {
            TaskResultReport r = envelope.getReport();
            logger.warn("Result ingest buffer full ({} capacity); falling back to synchronous envelope ingest taskId={}, messageId={}, adapterId={}",
                    queue.size(),
                    r != null ? r.getTaskId() : null,
                    r != null ? r.getMessageId() : null,
                    envelope.getAdapterId());
            return delegate.ingest(envelope);
        }
        return offered;
    }

    /**
     * Signals the drainer to stop, waits for queued items to be processed, then
     * returns. Must be called before stopping the engine so in-flight results are
     * not lost.
     */
    public void shutdown() {
        shutdown = true;
        drainerThread.interrupt();
        try {
            drainerThread.join(DRAIN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (drainerThread.isAlive()) {
            logger.warn("Result ingest buffer drainer did not finish within {}ms; {} item(s) may be unprocessed",
                    DRAIN_JOIN_MILLIS, queue.size());
        }
    }

    int pendingCount() {
        return queue.size();
    }

    private void drain() {
        List<Object> batch = new ArrayList<>(MAX_DRAIN_BATCH);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Short poll so that a shutdown signal is not delayed more than 200 ms.
                Object item = queue.poll(200, TimeUnit.MILLISECONDS);
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
        // Drain any items that arrived between the interrupt and the loop exit.
        batch.clear();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            processBatch(batch);
        }
    }

    private void processBatch(List<Object> batch) {
        for (Object item : batch) {
            process(item);
        }
    }

    private void process(Object item) {
        if (item instanceof TaskResultReport report) {
            delegate.ingest(report);
            return;
        }
        if (item instanceof TransportResultEnvelope envelope) {
            delegate.ingest(envelope);
            return;
        }
        throw new IllegalStateException("Unsupported buffered result item type: " + item);
    }
}
