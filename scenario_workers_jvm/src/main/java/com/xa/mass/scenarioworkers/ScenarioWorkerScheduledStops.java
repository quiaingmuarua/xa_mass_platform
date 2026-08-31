package com.xa.mass.scenarioworkers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns the finite, process-local scheduled-stop controls for the Lab. */
final class ScenarioWorkerScheduledStops implements AutoCloseable {

    static final long MAX_DELAY_MILLIS = Duration.ofDays(1).toMillis();

    private static final System.Logger LOGGER = System.getLogger(
            ScenarioWorkerScheduledStops.class.getName()
    );

    private final ScenarioWorkers workers;
    private final ScheduledExecutorService scheduler;
    private final Map<WorkerCoordinate, ScheduledStop> scheduled =
            new LinkedHashMap<>();

    private boolean closed;

    ScenarioWorkerScheduledStops(ScenarioWorkers workers) {
        this.workers = Objects.requireNonNull(workers, "workers");
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(
                    task,
                    "scenario-worker-lab-faults"
            );
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized boolean schedule(
            String workerGroupId,
            String clientWorkerKey,
            long delayMillis
    ) {
        ensureOpen();
        if (delayMillis < 1L || delayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException(
                    "delayMillis must be between 1 and "
                            + MAX_DELAY_MILLIS
            );
        }
        workers.workerSnapshot(workerGroupId, clientWorkerKey, false);
        WorkerCoordinate coordinate = new WorkerCoordinate(
                workerGroupId,
                clientWorkerKey
        );
        if (scheduled.containsKey(coordinate)) {
            return false;
        }
        long stopAtEpochMillis = Math.addExact(
                System.currentTimeMillis(),
                delayMillis
        );
        ScheduledFuture<?> future = scheduler.schedule(
                () -> fire(coordinate),
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        scheduled.put(
                coordinate,
                new ScheduledStop(stopAtEpochMillis, future)
        );
        return true;
    }

    synchronized boolean cancel(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ensureOpen();
        workers.workerSnapshot(workerGroupId, clientWorkerKey, false);
        ScheduledStop removed = scheduled.remove(new WorkerCoordinate(
                workerGroupId,
                clientWorkerKey
        ));
        if (removed == null) {
            return false;
        }
        removed.future().cancel(false);
        return true;
    }

    synchronized Long scheduledStopAtEpochMillis(
            String workerGroupId,
            String clientWorkerKey
    ) {
        ScheduledStop value = scheduled.get(new WorkerCoordinate(
                workerGroupId,
                clientWorkerKey
        ));
        return value == null ? null : value.stopAtEpochMillis();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            for (ScheduledStop value : scheduled.values()) {
                value.future().cancel(false);
            }
            scheduled.clear();
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Scenario Worker scheduled-stop owner did not close"
                );
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void fire(WorkerCoordinate coordinate) {
        synchronized (this) {
            if (closed || scheduled.remove(coordinate) == null) {
                return;
            }
        }
        try {
            workers.stopWorker(
                    coordinate.workerGroupId(),
                    coordinate.clientWorkerKey()
            );
        } catch (RuntimeException error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Scheduled Scenario Worker stop failed",
                    error
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario Worker scheduled-stop owner is closed"
            );
        }
    }

    private record WorkerCoordinate(
            String workerGroupId,
            String clientWorkerKey
    ) {
    }

    private record ScheduledStop(
            long stopAtEpochMillis,
            ScheduledFuture<?> future
    ) {
    }
}
