package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

public final class WorkerDeliveryAdapterLoop {

    private final WorkerDeliveryAdapter adapter;
    private final Duration interval;
    private ScheduledExecutorService executor;

    public WorkerDeliveryAdapterLoop(
            WorkerDeliveryAdapter adapter,
            Duration interval
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "interval must be positive"
            );
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-delivery-adapter"
            );
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::dispatchSafely,
                0,
                interval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
        adapter.close();
    }

    boolean isRunning() {
        return executor != null;
    }

    private void dispatchSafely() {
        try {
            adapter.dispatchOnce();
        } catch (RuntimeException ignored) {
            // The next bounded round retries pending results and Gateway I/O.
        }
    }
}
