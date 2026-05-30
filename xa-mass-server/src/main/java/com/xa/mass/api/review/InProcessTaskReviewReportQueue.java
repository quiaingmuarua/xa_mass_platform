package com.xa.mass.api.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process best-effort review materialization queue.
 */
public final class InProcessTaskReviewReportQueue implements TaskReviewReportQueue {

    private static final Logger log = LoggerFactory.getLogger(InProcessTaskReviewReportQueue.class);
    private static final int DEFAULT_CAPACITY = Integer.getInteger("xa.mass.review.reportQueueCapacity", 8192);

    private final TaskReviewMaterializer materializer;
    private final LinkedBlockingQueue<TaskReviewReportEvent> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final Object idleMonitor = new Object();
    private final Thread worker;

    private volatile boolean applying;

    public InProcessTaskReviewReportQueue(TaskReviewMaterializer materializer) {
        this(materializer, DEFAULT_CAPACITY);
    }

    public InProcessTaskReviewReportQueue(TaskReviewMaterializer materializer, int capacity) {
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.queue = new LinkedBlockingQueue<>(Math.max(1, capacity));
        this.worker = new Thread(this::runLoop, "task-review-report-queue");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean submit(TaskReviewReportEvent event) {
        if (event == null || !running.get()) {
            rejected.incrementAndGet();
            return false;
        }
        boolean accepted = queue.offer(event);
        if (accepted) {
            submitted.incrementAndGet();
            signalIdleStateChanged();
        } else {
            rejected.incrementAndGet();
        }
        return accepted;
    }

    @Override
    public boolean awaitIdle(Duration timeout) {
        long timeoutMillis = timeout == null ? 0L : Math.max(0L, timeout.toMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (idleMonitor) {
            while (!isIdle()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    idleMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public TaskReviewReportQueueStats snapshotStats() {
        return new TaskReviewReportQueueStats(
                submitted.get(),
                rejected.get(),
                applied.get(),
                failed.get(),
                queue.size() + (applying ? 1L : 0L),
                lastError.get()
        );
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
        signalIdleStateChanged();
    }

    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                TaskReviewReportEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) {
                    signalIdleStateChanged();
                    continue;
                }
                apply(event);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        signalIdleStateChanged();
    }

    private void apply(TaskReviewReportEvent event) {
        applying = true;
        signalIdleStateChanged();
        try {
            materializer.apply(event);
            applied.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            lastError.set(e.getMessage());
            log.warn("Task review materialization failed: eventType={}, taskId={}, reason={}",
                    event.getClass().getSimpleName(), event.taskId(), e.getMessage(), e);
        } finally {
            applying = false;
            signalIdleStateChanged();
        }
    }

    private boolean isIdle() {
        return queue.isEmpty() && !applying;
    }

    private void signalIdleStateChanged() {
        synchronized (idleMonitor) {
            idleMonitor.notifyAll();
        }
    }
}
