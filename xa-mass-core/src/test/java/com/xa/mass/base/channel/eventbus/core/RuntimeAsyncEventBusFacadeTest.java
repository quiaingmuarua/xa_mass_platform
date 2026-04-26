package com.xa.mass.base.channel.eventbus.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAsyncEventBusFacadeTest {

    @Test
    void dispatchesAnnotatedListenerAsynchronously() throws Exception {
        EventBusConfig config = EventBusConfig.defaultConfig()
                .setCorePoolSize(1)
                .setMaxPoolSize(1)
                .setQueueCapacity(10)
                .setHandlerTimeoutSeconds(5);
        RuntimeAsyncEventBusFacade eventBus = new RuntimeAsyncEventBusFacade(config);
        CountDownLatch handled = new CountDownLatch(1);
        RuntimeTestListener listener = new RuntimeTestListener(handled);

        try {
            eventBus.register(listener);
            eventBus.post(new RuntimeTestEvent("async"));

            assertTrue(handled.await(1, TimeUnit.SECONDS));
            assertTrue(listener.wasHandledOnAsyncThread());
            assertEquals(1, eventBus.getStatistics().getCompletedEvents());
        } finally {
            eventBus.shutdown();
        }
    }

    @Test
    void recordsTimeoutForStuckHandler() throws Exception {
        EventBusConfig config = EventBusConfig.defaultConfig()
                .setCorePoolSize(1)
                .setMaxPoolSize(1)
                .setQueueCapacity(10)
                .setHandlerTimeoutSeconds(1);
        RuntimeAsyncEventBusFacade eventBus = new RuntimeAsyncEventBusFacade(config);
        CountDownLatch entered = new CountDownLatch(1);

        try {
            eventBus.register(RuntimeTestEvent.class, event -> {
                entered.countDown();
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            eventBus.post(new RuntimeTestEvent("timeout"));

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(waitForTimeout(eventBus));
            assertTrue(eventBus.getStatistics().getTimeoutEvents() > 0);
        } finally {
            eventBus.shutdown();
        }
    }

    @Test
    void recordsRejectedEventWhenQueueIsFull() {
        EventBusConfig config = EventBusConfig.defaultConfig()
                .setCorePoolSize(1)
                .setMaxPoolSize(1)
                .setQueueCapacity(10)
                .setHandlerTimeoutSeconds(5);
        RuntimeAsyncEventBusFacade eventBus = new RuntimeAsyncEventBusFacade(config);

        try {
            eventBus.shutdown();
            eventBus.post(new RuntimeTestEvent("rejected"));

            assertEquals(1, eventBus.getStatistics().getRejectedEvents());
        } finally {
            eventBus.shutdown();
        }
    }

    @Test
    void factoryUsesRuntimeEventBusOnly() {
        assertInstanceOf(RuntimeAsyncEventBusFacade.class, EventBusFactory.get("runtime"));
        assertThrows(IllegalArgumentException.class, () -> EventBusFactory.get("guava"));
        assertThrows(UnsupportedOperationException.class, () -> EventBusFactory.get("redis"));
    }

    private static boolean waitForTimeout(RuntimeAsyncEventBusFacade eventBus) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (eventBus.getStatistics().getTimeoutEvents() > 0) {
                return true;
            }
            Thread.sleep(25L);
        }
        return false;
    }

    public static final class RuntimeTestListener {
        private final CountDownLatch handled;
        private final AtomicBoolean asyncThread = new AtomicBoolean();

        RuntimeTestListener(CountDownLatch handled) {
            this.handled = handled;
        }

        @MassSubscribe
        public void onRuntimeTestEvent(RuntimeTestEvent event) {
            asyncThread.set(Thread.currentThread().getName().startsWith("runtime-event-handler-"));
            handled.countDown();
        }

        boolean wasHandledOnAsyncThread() {
            return asyncThread.get();
        }
    }

    public static final class RuntimeTestEvent implements MassEvent {
        private final String id;

        RuntimeTestEvent(String id) {
            this.id = id;
        }

        @Override
        public String getEventId() {
            return id;
        }

        @Override
        public Instant getTimestamp() {
            return Instant.now();
        }

        @Override
        public String getDescription() {
            return id;
        }
    }
}
