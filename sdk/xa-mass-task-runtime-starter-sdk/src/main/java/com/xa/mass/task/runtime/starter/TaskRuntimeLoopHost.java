package com.xa.mass.task.runtime.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class TaskRuntimeLoopHost implements AutoCloseable {

    private final TaskRuntimePortSet runtime;
    private List<TaskRuntimeLoop> loops;
    private final long intervalMillis;
    private final ThreadFactory threadFactory;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");
    private ScheduledThreadPoolExecutor executor;
    private List<ScheduledFuture<?>> futures = List.of();

    public TaskRuntimeLoopHost(
            TaskRuntimePortSet runtime,
            List<TaskRuntimeLoop> loops,
            long intervalMillis,
            ThreadFactory threadFactory,
            LongSupplier clock
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.loops = List.copyOf(loops == null ? List.of() : loops);
        this.intervalMillis = intervalMillis <= 0 ? 100L : intervalMillis;
        this.threadFactory = threadFactory == null ? defaultThreadFactory() : threadFactory;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        assertUniqueLoopNames(this.loops);
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (loops.isEmpty()) {
            return;
        }
        scheduleLoops(loops);
    }

    public synchronized void registerLoops(List<TaskRuntimeLoop> additionalLoops) {
        var loopsToRegister = List.copyOf(additionalLoops == null ? List.of() : additionalLoops);
        if (loopsToRegister.isEmpty()) {
            return;
        }
        var merged = new ArrayList<TaskRuntimeLoop>(loops);
        merged.addAll(loopsToRegister);
        assertUniqueLoopNames(merged);
        loops = List.copyOf(merged);
        if (running.get()) {
            scheduleLoops(loopsToRegister);
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (var future : futures) {
            future.cancel(true);
        }
        futures = List.of();
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    public TaskRuntimeLoopHostStatus status() {
        return new TaskRuntimeLoopHostStatus(
                running.get(),
                loops.stream().map(TaskRuntimeLoop::name).toList(),
                lastFailure.get());
    }

    @Override
    public void close() {
        stop();
    }

    private void runLoop(TaskRuntimeLoop loop) {
        try {
            loop.runOnce(new TaskRuntimeLoopContext(runtime, clock.getAsLong()));
        } catch (RuntimeException exception) {
            lastFailure.set(loop.name() + ": " + exception.getMessage());
        }
    }

    private void scheduleLoops(List<TaskRuntimeLoop> loopsToSchedule) {
        if (loopsToSchedule.isEmpty()) {
            return;
        }
        var scheduledExecutor = ensureExecutor();
        scheduledExecutor.setCorePoolSize(Math.max(scheduledExecutor.getCorePoolSize(), loops.size()));
        var scheduled = new ArrayList<ScheduledFuture<?>>(futures);
        for (var loop : loopsToSchedule) {
            scheduled.add(scheduledExecutor.scheduleWithFixedDelay(
                    () -> runLoop(loop),
                    0L,
                    loopIntervalMillis(loop),
                    TimeUnit.MILLISECONDS));
        }
        futures = List.copyOf(scheduled);
    }

    private ScheduledThreadPoolExecutor ensureExecutor() {
        if (executor == null) {
            executor = new ScheduledThreadPoolExecutor(Math.max(1, loops.size()), threadFactory);
            executor.setRemoveOnCancelPolicy(true);
        }
        return executor;
    }

    private long loopIntervalMillis(TaskRuntimeLoop loop) {
        long loopIntervalMillis = loop.intervalMillis();
        return loopIntervalMillis <= 0L ? intervalMillis : loopIntervalMillis;
    }

    private static void assertUniqueLoopNames(List<TaskRuntimeLoop> loops) {
        var names = new LinkedHashSet<String>();
        for (var loop : loops) {
            if (!names.add(loop.name())) {
                throw new IllegalArgumentException("duplicate task runtime loop name: " + loop.name());
            }
        }
    }

    private static ThreadFactory defaultThreadFactory() {
        return task -> {
            var thread = new Thread(task, "xa-task-runtime-loop");
            thread.setDaemon(true);
            return thread;
        };
    }
}
