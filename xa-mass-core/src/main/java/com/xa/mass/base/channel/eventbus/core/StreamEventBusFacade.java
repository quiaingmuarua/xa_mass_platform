package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream-backed event bus facade with batching, ACK control, and runtime metrics.
 */
public class StreamEventBusFacade<T> implements EventBusFacade<T> {
    private static final Logger log = LoggerFactory.getLogger(StreamEventBusFacade.class);

    private final MessageStream<T> stream;
    private final MassEventDispatcher<T> dispatcher = new MassEventDispatcher<>();
    private final EventBusConfig config;
    private final ExecutorService consumerExecutor;
    private final ThreadPoolExecutor handlerExecutor;
    private volatile boolean running = true;

    private final AtomicLong processedMessages = new AtomicLong(0);
    private final AtomicLong failedMessages = new AtomicLong(0);
    private final AtomicLong timeoutMessages = new AtomicLong(0);

    public StreamEventBusFacade(MessageStream<T> stream, EventBusConfig config) {
        this.stream = stream;
        this.config = config;
        this.consumerExecutor = createConsumerExecutor();
        this.handlerExecutor = createHandlerExecutor();
        startListenerLoop();
        log.info("OptimizedStreamEventBusFacade started with config: {}", config);
    }

    public StreamEventBusFacade(MessageStream<T> stream) {
        this(stream, EventBusConfig.defaultConfig());
    }

    private ExecutorService createConsumerExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "optimized-stream-event-consumer");
            t.setDaemon(true);
            return t;
        });
    }

    private ThreadPoolExecutor createHandlerExecutor() {
        return new ThreadPoolExecutor(
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getKeepAliveTimeSeconds(),
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "event-handler-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    private void startListenerLoop() {
        consumerExecutor.submit(() -> {
            while (running) {
                try {
                    List<MessageStream.StreamMessage<T>> messages =
                            stream.pollBatch(config.getBatchSize(), config.getBatchTimeoutMs(), TimeUnit.MILLISECONDS);

                    if (messages != null && !messages.isEmpty()) {
                        processBatchSafely(messages);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running) {
                        log.error("Stream listener error", e);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            log.info("EventBus consumer thread exited.");
        });
    }

    private void processBatchSafely(List<MessageStream.StreamMessage<T>> messages) {
        List<String> toAck = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(messages.size());

        for (MessageStream.StreamMessage<T> msg : messages) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    dispatcher.dispatch(msg.getMessage());
                    processedMessages.incrementAndGet();
                    toAck.add(msg.getMessageId());
                } catch (Throwable e) {
                    failedMessages.incrementAndGet();
                    log.error("Failed to process message: {}", msg.getMessageId(), e);
                }
            }, handlerExecutor);
            futures.add(future);
        }

        try {
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            all.get(config.getHandlerTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            int notDone = 0;
            for (CompletableFuture<Void> future : futures) {
                if (!future.isDone()) {
                    notDone++;
                }
            }
            timeoutMessages.addAndGet(notDone);
            log.warn("Timeout: {} message(s) not processed within {}s", notDone, config.getHandlerTimeoutSeconds());
        } catch (Exception e) {
            log.error("Exception while waiting for batch processing", e);
        }

        if (!toAck.isEmpty()) {
            try {
                int acked = stream.ackBatch(toAck);
                log.debug("Successfully ACKed {} messages", acked);
            } catch (Exception e) {
                log.error("Failed to ACK messages", e);
            }
        }
    }

    @Override
    public void register(Object listener) {
        dispatcher.registerListener(listener);
        log.debug("Registered listener: {}", listener.getClass().getSimpleName());
    }

    @Override
    public void unregister(Object listener) {
        dispatcher.unregisterListener(listener);
        log.debug("Unregistered listener: {}", listener.getClass().getSimpleName());
    }

    @Override
    public <E extends T> void post(E event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        stream.offer(event);
    }

    @Override
    public <E extends T> void register(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("Register listener instances annotated with @MassSubscribe directly");
    }

    @Override
    public <E extends T> void unregister(Class<E> eventType, java.util.function.Consumer<E> handler) {
        throw new UnsupportedOperationException("Unregister listener instances annotated with @MassSubscribe directly");
    }

    @Override
    public void shutdown() {
        running = false;
        log.info("Shutting down OptimizedStreamEventBusFacade...");

        consumerExecutor.shutdownNow();
        try {
            consumerExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        handlerExecutor.shutdown();
        try {
            handlerExecutor.awaitTermination(config.getHandlerTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logFinalStatistics();
    }

    public EventBusStatistics getStatistics() {
        return new EventBusStatistics(
                processedMessages.get(),
                failedMessages.get(),
                timeoutMessages.get(),
                dispatcher.getTotalHandlerCount(),
                handlerExecutor.getActiveCount(),
                handlerExecutor.getQueue().size(),
                handlerExecutor.getCompletedTaskCount()
        );
    }

    private void logFinalStatistics() {
        EventBusStatistics stats = getStatistics();
        log.info("EventBus shutdown statistics: {}", stats);
    }

    public int getListenerCount() {
        return dispatcher.getTotalHandlerCount();
    }

    public int getHandlerCount(Class<?> eventType) {
        return dispatcher.getHandlerCount(eventType);
    }

    public EventBusConfig getConfig() {
        return config;
    }

    public String getStreamInfo() {
        return String.format("Stream: %s, Handlers: %d, Config: %s",
                stream.getName(), getListenerCount(), config);
    }

    public static class EventBusStatistics {
        private final long processedMessages;
        private final long failedMessages;
        private final long timeoutMessages;
        private final int totalHandlers;
        private final int activeThreads;
        private final int queuedTasks;
        private final long completedTasks;

        public EventBusStatistics(long processedMessages, long failedMessages, long timeoutMessages,
                                  int totalHandlers, int activeThreads, int queuedTasks, long completedTasks) {
            this.processedMessages = processedMessages;
            this.failedMessages = failedMessages;
            this.timeoutMessages = timeoutMessages;
            this.totalHandlers = totalHandlers;
            this.activeThreads = activeThreads;
            this.queuedTasks = queuedTasks;
            this.completedTasks = completedTasks;
        }

        public long getProcessedMessages() {
            return processedMessages;
        }

        public long getFailedMessages() {
            return failedMessages;
        }

        public long getTimeoutMessages() {
            return timeoutMessages;
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

        @Override
        public String toString() {
            return "EventBusStatistics{" +
                    "processedMessages=" + processedMessages +
                    ", failedMessages=" + failedMessages +
                    ", timeoutMessages=" + timeoutMessages +
                    ", totalHandlers=" + totalHandlers +
                    ", activeThreads=" + activeThreads +
                    ", queuedTasks=" + queuedTasks +
                    ", completedTasks=" + completedTasks +
                    '}';
        }
    }
}
