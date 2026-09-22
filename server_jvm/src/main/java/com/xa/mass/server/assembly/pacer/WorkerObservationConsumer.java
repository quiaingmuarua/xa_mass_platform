package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

/** Fixed routing and lossy serial delivery; handlers own their effects, never scheduling. */
final class WorkerObservationConsumer implements SmartLifecycle, DisposableBean {
    static final int QUEUE_BATCHES = 256;
    static final int PROCESS_BATCHES = 16;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final Map<String, Map<EventKey, List<FunctionHandler>>> handlersByGroup;
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed;
    private final AtomicBoolean running;
    private ArrayBlockingQueue<WorkerObservation> queue;
    private Thread consumer;
    private long stopDeadline;

    WorkerObservationConsumer(Map<String, Map<EventKey, List<FunctionHandler>>> handlersByGroup,
                              AtomicBoolean running, AtomicLong failed) {
        var groups = new LinkedHashMap<String, Map<EventKey, List<FunctionHandler>>>();
        handlersByGroup.forEach((group, routes) -> {
            var selections = new LinkedHashMap<EventKey, List<FunctionHandler>>();
            routes.forEach((key, handlers) -> {
                var snapshot = List.copyOf(handlers);
                var seen = Collections.newSetFromMap(new IdentityHashMap<FunctionHandler, Boolean>());
                for (var handler : snapshot) {
                    if (!seen.add(handler)) {
                        throw new IllegalArgumentException("operation=workerObservation.assemble duplicate handler selection");
                    }
                }
                if (!snapshot.isEmpty()) selections.put(Objects.requireNonNull(key), snapshot);
            });
            if (!selections.isEmpty()) {
                groups.put(Objects.requireNonNull(group), Collections.unmodifiableMap(selections));
            }
        });
        this.handlersByGroup = Collections.unmodifiableMap(groups);
        this.running = Objects.requireNonNull(running);
        this.failed = Objects.requireNonNull(failed);
    }

    boolean enabled() { return !handlersByGroup.isEmpty(); }

    private List<FunctionHandler> handlersFor(WorkerObservation observation) {
        var routes = handlersByGroup.get(observation.workerGroupId());
        return routes == null ? List.of() : routes.getOrDefault(
                new EventKey(observation.messageEventName(), observation.observationEventName()), List.of());
    }

    // No I/O or user callback under this short lifecycle/admission gate.
    synchronized void accept(WorkerObservation observation) {
        if (handlersFor(observation).isEmpty()) return;
        if (!running.get() || !queue.offer(observation)) dropped.incrementAndGet();
        else enqueued.incrementAndGet();
    }

    @Override public synchronized void start() {
        if (!enabled() || running.get()) return;
        if (consumer != null && consumer.isAlive()) {
            throw new IllegalStateException("operation=workerObservation.start previous consumer has not stopped");
        }
        queue = new ArrayBlockingQueue<>(QUEUE_BATCHES);
        stopDeadline = 0;
        consumer = Thread.ofPlatform().name("worker-observation-consumer").daemon(false).unstarted(this::consume);
        running.set(true);
        try { consumer.start(); }
        catch (RuntimeException | Error error) {
            running.set(false);
            queue = null;
            consumer = null;
            throw error;
        }
    }

    private void consume() {
        try {
            while (running.get()) {
                var batch = new ArrayList<WorkerObservation>(PROCESS_BATCHES);
                batch.add(queue.take());
                queue.drainTo(batch, PROCESS_BATCHES - 1);
                if (!running.get()) break;
                try { process(batch); }
                catch (RuntimeException ignored) { failed.incrementAndGet(); }
                recordDiagnostics();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            running.set(false);
        }
    }

    private void process(List<WorkerObservation> batch) {
        var byHandler = new IdentityHashMap<FunctionHandler, List<WorkerObservation>>();
        var order = new ArrayList<FunctionHandler>();
        for (var observation : batch) {
            for (var handler : handlersFor(observation)) {
                var notices = byHandler.get(handler);
                if (notices == null) {
                    notices = new ArrayList<>();
                    byHandler.put(handler, notices);
                    order.add(handler);
                }
                notices.add(observation);
            }
        }
        for (var handler : order) {
            if (!running.get()) return;
            try { handler.handle(List.copyOf(byHandler.get(handler))); }
            catch (RuntimeException ignored) { failed.incrementAndGet(); }
        }
    }

    @Override public void stop() {
        Thread thread;
        long deadline;
        synchronized (this) {
            running.set(false);
            thread = consumer;
            if (thread == null) return;
            if (stopDeadline == 0) stopDeadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
            deadline = stopDeadline;
            dropped.addAndGet(queue.size());
            queue.clear();
            thread.interrupt();
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) thread.join(Duration.ofNanos(remaining));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        recordDiagnostics();
        if (thread.isAlive()) {
            throw new IllegalStateException("operation=workerObservation.stop consumer exceeded shutdown budget");
        }
        synchronized (this) {
            if (consumer == thread) { consumer = null; queue = null; }
        }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MIN_VALUE; }
    @Override public void destroy() { stop(); }

    private void recordDiagnostics() {
        try {
            var event = new Summary();
            if (!event.isEnabled()) return;
            event.enqueuedBatches = enqueued.get();
            event.droppedBatches = dropped.get();
            event.processingFailures = failed.get();
            event.commit();
        } catch (RuntimeException ignored) { /* Diagnostics cannot control dispatch or property writes. */ }
    }

    @Name("xa.mass.WorkerObservation") @Category("XA Mass") @Enabled(false) @StackTrace(false)
    static final class Summary extends Event {
        public long enqueuedBatches;
        public long droppedBatches;
        public long processingFailures;
    }
}
