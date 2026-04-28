package com.xa.mass.base.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java 21 virtual-thread runtime executor with explicit admission control.
 */
public final class VirtualThreadRuntimeTaskExecutor implements RuntimeTaskExecutor {
    private final ExecutorService delegate;
    private final Semaphore permits;
    private final int maxPendingTasks;
    private final AtomicLong submittedTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong rejectedTasks = new AtomicLong();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private volatile boolean running = true;

    public VirtualThreadRuntimeTaskExecutor(String threadNamePrefix, int maxPendingTasks) {
        if (maxPendingTasks < 1) {
            throw new IllegalArgumentException("maxPendingTasks must be greater than 0");
        }
        String prefix = threadNamePrefix == null || threadNamePrefix.isBlank()
                ? "runtime-vt-"
                : threadNamePrefix;
        ThreadFactory factory = Thread.ofVirtual().name(prefix, 0).factory();
        this.delegate = Executors.newThreadPerTaskExecutor(factory);
        this.permits = new Semaphore(maxPendingTasks);
        this.maxPendingTasks = maxPendingTasks;
    }

    @Override
    public Future<?> submit(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        return submit(Executors.callable(task, null));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        acquirePermit();
        submittedTasks.incrementAndGet();
        try {
            return delegate.submit(() -> {
                activeTasks.incrementAndGet();
                try {
                    return task.call();
                } finally {
                    activeTasks.decrementAndGet();
                    completedTasks.incrementAndGet();
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            rejectedTasks.incrementAndGet();
            permits.release();
            throw e;
        }
    }

    @Override
    public void shutdown() {
        running = false;
        delegate.shutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public RuntimeTaskExecutorStatistics getStatistics() {
        int pendingTasks = maxPendingTasks - permits.availablePermits();
        return new RuntimeTaskExecutorStatistics(
                submittedTasks.get(),
                completedTasks.get(),
                rejectedTasks.get(),
                activeTasks.get(),
                pendingTasks,
                maxPendingTasks);
    }

    private void acquirePermit() {
        if (!running) {
            rejectedTasks.incrementAndGet();
            throw new RejectedExecutionException("runtime executor is stopped");
        }
        if (!permits.tryAcquire()) {
            rejectedTasks.incrementAndGet();
            throw new RejectedExecutionException("runtime executor admission limit reached");
        }
    }
}
