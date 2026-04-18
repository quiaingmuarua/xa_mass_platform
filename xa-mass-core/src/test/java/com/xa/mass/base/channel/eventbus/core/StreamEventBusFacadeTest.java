package com.xa.mass.base.channel.eventbus.core;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamEventBusFacadeTest {
    private StreamEventBusFacade<MassEvent> eventBus;
    private TestListener testListener;
    private AnotherListener anotherListener;
    private List<String> received;

    public static class TestEvent implements MassEvent {
        private final String message;

        public TestEvent(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String getEventId() {
            return "test-" + message;
        }

        @Override
        public Instant getTimestamp() {
            return Instant.now();
        }

        @Override
        public String getDescription() {
            return message;
        }
    }

    public static class AnotherEvent implements MassEvent {
        private final int number;

        public AnotherEvent(int number) {
            this.number = number;
        }

        public int getNumber() {
            return number;
        }

        @Override
        public String getEventId() {
            return "another-" + number;
        }

        @Override
        public Instant getTimestamp() {
            return Instant.now();
        }

        @Override
        public String getDescription() {
            return "AnotherEvent: " + number;
        }
    }

    public class TestListener {
        @MassSubscribe
        public void onTestEvent(TestEvent event) {
            received.add("Test:" + event.getMessage());
        }
    }

    public class AnotherListener {
        @MassSubscribe
        public void onAnotherEvent(AnotherEvent event) {
            received.add("Another:" + event.getNumber());
        }
    }

    @BeforeEach
    public void setUp() {
        MessageStream<MassEvent> stream = new InMemoryMessageStream<>("test-bus", MassEvent.class);
        EventBusConfig testConfig = EventBusConfig.defaultConfig()
                .setBatchSize(1)
                .setBatchTimeoutMs(100L);
        eventBus = new StreamEventBusFacade<>(stream, testConfig);
        received = new ArrayList<>();
        testListener = new TestListener();
        anotherListener = new AnotherListener();
    }

    @AfterEach
    public void tearDown() {
        eventBus.shutdown();
    }

    @Test
    public void testRegisterAndPost() throws Exception {
        eventBus.register(testListener);
        eventBus.post(new TestEvent("hello"));
        Thread.sleep(200);
        assertTrue(received.contains("Test:hello"));
    }

    @Test
    public void testUnregister() throws Exception {
        eventBus.register(testListener);
        eventBus.post(new TestEvent("before"));
        Thread.sleep(100);
        eventBus.unregister(testListener);
        eventBus.post(new TestEvent("after"));
        Thread.sleep(200);
        assertTrue(received.contains("Test:before"));
        assertFalse(received.contains("Test:after"));
    }

    @Test
    public void testMultiTypeListener() throws Exception {
        eventBus.register(testListener);
        eventBus.register(anotherListener);
        eventBus.post(new TestEvent("foo"));
        eventBus.post(new AnotherEvent(123));
        Thread.sleep(200);
        assertTrue(received.contains("Test:foo"));
        assertTrue(received.contains("Another:123"));
    }

    @Test
    public void testBatchEvents() throws Exception {
        eventBus.register(testListener);
        for (int i = 0; i < 10; i++) {
            eventBus.post(new TestEvent("batch-" + i));
        }
        Thread.sleep(500);
        for (int i = 0; i < 10; i++) {
            assertTrue(received.contains("Test:batch-" + i));
        }
    }

    @Test
    public void testListenerNotReceiveAfterUnregister() throws Exception {
        eventBus.register(testListener);
        eventBus.post(new TestEvent("first"));
        Thread.sleep(100);
        eventBus.unregister(testListener);
        eventBus.post(new TestEvent("second"));
        Thread.sleep(200);
        assertTrue(received.contains("Test:first"));
        assertFalse(received.contains("Test:second"));
    }
}
