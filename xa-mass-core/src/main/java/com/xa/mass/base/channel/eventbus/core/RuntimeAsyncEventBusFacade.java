package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runtime-owned asynchronous event bus for non-inline platform event handlers.
 *
 * <p>This facade keeps event delivery off the engine lifecycle thread, while
 * bounding handler admission and surfacing stuck handler signals through metrics.
 * Timeout cancellation is cooperative: handlers must still set timeouts on
 * blocking I/O and honor interrupts to release the executor thread promptly.
 */
public final class RuntimeAsyncEventBusFacade implements EventBusFacade<MassEvent> {
    private static final Logger log = LoggerFactory.getLogger(RuntimeAsyncEventBusFacade.class);

    private final MassEventDispatcher<MassEvent> dispatcher = new MassEventDispatcher<>();
    private final Map<Class<?>, List<Consumer<? extends MassEvent>>> typedHandlers = new ConcurrentHashMap<>();
    private final EventBusConfig config;
    private final RuntimeTaskExecutor handlerExecutor;
    private final ScheduledExecutorService timeoutWatcher;
    private final AtomicLong postedEvents = new AtomicLong();
    private final AtomicLong completedEvents = new AtomicLong();
    private final AtomicLong failedEvents = new AtomicLong();
    private final AtomicLong timeoutEvents = new AtomicLong();
    private final AtomicLong rejectedEvents = new AtomicLong();
    private volatile boolean running = true;

    public RuntimeAsyncEventBusFacade() {
        this(EventBusConfig.defaultConfig());
    }

    public RuntimeAsyncEventBusFacade(EventBusConfig config) {
        this.config = config != null ? config : EventBusConfig.defaultConfig();
        this.handlerExecutor = new VirtualThreadRuntimeTaskExecutor(
                "runtime-event-handler-",
                this.config.getQueueCapacity());
        this.timeoutWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "runtime-event-timeout-watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void register(Object listener) {
        dispatcher.registerListener(listener);
    }

    @Override
    public void unregister(Object listener) {
        dispatcher.unregisterListener(listener);
    }

    @Override
    public <E extends MassEvent> void register(Class<E> eventType, Consumer<E> handler) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler cannot be null");
        }
        typedHandlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public <E extends MassEvent> void unregister(Class<E> eventType, Consumer<E> handler) {
        if (eventType == null || handler == null) {
            return;
        }
        List<Consumer<? extends MassEvent>> handlers = typedHandlers.get(eventType);
        if (handlers != null) {
            handlers.removeIf(existing -> existing == handler || existing.equals(handler));
            if (handlers.isEmpty()) {
                typedHandlers.remove(eventType, handlers);
            }
        }
    }

    @Override
    public <E extends MassEvent> void post(E event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        if (!running) {
            rejectedEvents.incrementAndGet();
            log.warn("Runtime event bus is stopped; dropping event {}", event.getEventId());
            return;
        }

        postedEvents.incrementAndGet();
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        try {
            Future<?> future = handlerExecutor.submit(() -> dispatchEvent(event));
            futureRef.set(future);
            timeoutWatcher.schedule(() -> markTimeoutIfStillRunning(event, futureRef.get()),
                    config.getHandlerTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            rejectedEvents.incrementAndGet();
            log.warn("Runtime event bus rejected event {} of type {}",
                    event.getEventId(), event.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void shutdown() {
        running = false;
        timeoutWatcher.shutdownNow();
        handlerExecutor.shutdown();
        try {
            handlerExecutor.awaitTermination(config.getHandlerTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public RuntimeEventBusStatistics getStatistics() {
        RuntimeTaskExecutorStatistics executorStats = handlerExecutor.getStatistics();
        return new RuntimeEventBusStatistics(
                postedEvents.get(),
                completedEvents.get(),
                failedEvents.get(),
                timeoutEvents.get(),
                rejectedEvents.get(),
                dispatcher.getTotalHandlerCount() + typedHandlers.values().stream().mapToInt(List::size).sum(),
                executorStats.getActiveTasks(),
                Math.max(0, executorStats.getPendingTasks() - executorStats.getActiveTasks()),
                executorStats.getCompletedTasks());
    }

    public EventBusConfig getConfig() {
        return config;
    }

    private void dispatchEvent(MassEvent event) {
        try {
            dispatcher.dispatch(event);
            dispatchTypedHandlers(event);
            completedEvents.incrementAndGet();
        } catch (Throwable e) {
            failedEvents.incrementAndGet();
            log.error("Runtime event handler failed for event {} of type {}",
                    event.getEventId(), event.getClass().getSimpleName(), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchTypedHandlers(MassEvent event) {
        List<Consumer<? extends MassEvent>> handlers = typedHandlers.get(event.getClass());
        if (handlers == null || handlers.isEmpty()) {
            return;
        }
        RuntimeException firstException = null;
        for (Consumer handler : handlers) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                if (firstException == null) {
                    firstException = e;
                }
                log.error("Runtime event typed handler failed for event {} of type {}",
                        event.getEventId(), event.getClass().getSimpleName(), e);
            }
        }
        if (firstException != null) {
            throw firstException;
        }
    }

    private void markTimeoutIfStillRunning(MassEvent event, Future<?> future) {
        if (future != null && !future.isDone()) {
            timeoutEvents.incrementAndGet();
            future.cancel(true);
            log.warn("Runtime event handler timed out after {}s for event {} of type {}",
                    config.getHandlerTimeoutSeconds(), event.getEventId(), event.getClass().getSimpleName());
        }
    }

    public static final class RuntimeEventBusStatistics {
        private final long postedEvents;
        private final long completedEvents;
        private final long failedEvents;
        private final long timeoutEvents;
        private final long rejectedEvents;
        private final int totalHandlers;
        private final int activeThreads;
        private final int queuedTasks;
        private final long completedTasks;

        RuntimeEventBusStatistics(long postedEvents,
                                  long completedEvents,
                                  long failedEvents,
                                  long timeoutEvents,
                                  long rejectedEvents,
                                  int totalHandlers,
                                  int activeThreads,
                                  int queuedTasks,
                                  long completedTasks) {
            this.postedEvents = postedEvents;
            this.completedEvents = completedEvents;
            this.failedEvents = failedEvents;
            this.timeoutEvents = timeoutEvents;
            this.rejectedEvents = rejectedEvents;
            this.totalHandlers = totalHandlers;
            this.activeThreads = activeThreads;
            this.queuedTasks = queuedTasks;
            this.completedTasks = completedTasks;
        }

        public long getPostedEvents() {
            return postedEvents;
        }

        public long getCompletedEvents() {
            return completedEvents;
        }

        public long getFailedEvents() {
            return failedEvents;
        }

        public long getTimeoutEvents() {
            return timeoutEvents;
        }

        public long getRejectedEvents() {
            return rejectedEvents;
        }

        public int getTotalHandlers() {
            return totalHandlers;
        }

        public int getActiveThreads() {
            return activeThreads;
        }

        public int getQueuedTasks() {
            return queuedTasks;
        }

        public long getCompletedTasks() {
            return completedTasks;
        }
    }
}
