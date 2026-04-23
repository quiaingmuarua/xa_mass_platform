package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.test.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestPayload {
    public String name;
    public int value;

    public TestPayload() {
    }

    public TestPayload(String name, int value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TestPayload that = (TestPayload) o;
        return value == that.value && java.util.Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, value);
    }
}

public class MessageStreamProviderRegistryTest {
    private static final String QUEUE_KEY = "test-provider-registry";
    private static final String GROUP = "test-group";
    private static final String CONSUMER = "test-consumer";

    @BeforeEach
    public void setUp() {
        RedisTestSupport.initLocalRedisOrSkip();
        MessageStreamProviderRegistry.clearCache();
    }

    @AfterEach
    public void tearDown() {
        MessageStreamProviderRegistry.clearCache();
    }

    @Test
    public void testCreateInMemoryStream() {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, String.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }

    @Test
    public void testCreateRedisStream() {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.REDIS, QUEUE_KEY, String.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof LettuceRedisStream);
    }

    @Test
    public void testStreamCache() {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<String> s1 = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, String.class, params);
        MessageStream<String> s2 = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, String.class, params);
        assertSame(s1, s2);
    }

    @Test
    public void testClearCache() {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<String> s1 = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, String.class, params);
        MessageStreamProviderRegistry.clearCache();
        MessageStream<String> s2 = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, String.class, params);
        assertNotSame(s1, s2);
    }

    @Test
    public void testRegisterCustomProvider() {
        MessageStreamProviderRegistry.register(
                MessageProviderType.valueOf("CUSTOM"),
                (queueKey, messageType, extraParams) -> new InMemoryMessageStream<>(queueKey, messageType));
        MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.valueOf("CUSTOM"), QUEUE_KEY, String.class, null);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }

    @Test
    public void testCreateInMemoryStreamWithObject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<TestPayload> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
        TestPayload payload = new TestPayload("foo", 42);
        stream.offer(payload);
        TestPayload polled = stream.poll(1, java.util.concurrent.TimeUnit.SECONDS).getMessage();
        assertEquals(payload, polled);
    }

    @Test
    public void testCreateRedisStreamWithObject() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("group", GROUP);
        params.put("consumerName", CONSUMER);
        MessageStream<TestPayload> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.REDIS, QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof LettuceRedisStream);
        TestPayload payload = new TestPayload("bar", 99);
        stream.offer(payload);
        TestPayload polled = stream.poll(1, java.util.concurrent.TimeUnit.SECONDS).getMessage();
        assertEquals(payload, polled);
    }

    @Test
    public void testCustomProviderWithObject() throws Exception {
        MessageStreamProviderRegistry.register(
                MessageProviderType.CUSTOM,
                (queueKey, messageType, extraParams) -> new InMemoryMessageStream<>(queueKey, messageType));
        MessageStream<TestPayload> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.CUSTOM, QUEUE_KEY, TestPayload.class, null);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
        TestPayload payload = new TestPayload("baz", 7);
        stream.offer(payload);
        TestPayload polled = stream.poll(1, java.util.concurrent.TimeUnit.SECONDS).getMessage();
        assertEquals(payload, polled);
    }
}
