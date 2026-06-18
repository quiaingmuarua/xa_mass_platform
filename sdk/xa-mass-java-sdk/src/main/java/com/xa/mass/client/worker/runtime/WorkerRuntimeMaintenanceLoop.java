package com.xa.mass.client.worker.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class WorkerRuntimeMaintenanceLoop {
    private final ScheduledExecutorService executor;
    private final List<FixedDelayTask> fixedDelayTasks = new ArrayList<>();
    private boolean started;

    WorkerRuntimeMaintenanceLoop(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor is required");
    }

    void addFixedDelayTask(String name, Duration initialDelay, Duration interval, Runnable task) {
        if (started) {
            throw new IllegalStateException("maintenance loop already started");
        }
        fixedDelayTasks.add(new FixedDelayTask(
                requireText(name, "name"),
                requireNonNegative(initialDelay, "initialDelay"),
                requirePositive(interval, "interval"),
                Objects.requireNonNull(task, "task is required")
        ));
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        for (FixedDelayTask task : fixedDelayTasks) {
            executor.scheduleWithFixedDelay(task.action(),
                    task.initialDelay().toMillis(),
                    task.interval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static Duration requireNonNegative(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        if (value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private record FixedDelayTask(String name, Duration initialDelay, Duration interval, Runnable action) {
    }
}
