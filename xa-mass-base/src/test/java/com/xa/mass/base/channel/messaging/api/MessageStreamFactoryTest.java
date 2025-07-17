package com.xa.mass.base.channel.messaging.api;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

// 测试负载对象
class TestPayload {
    public String name;
    public int value;
    public TestPayload() {}
    public TestPayload(String name, int value) { this.name = name; this.value = value; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestPayload that = (TestPayload) o;
        return value == that.value && java.util.Objects.equals(name, that.name);
    }
    @Override public int hashCode() { return java.util.Objects.hash(name, value); }
}

public class MessageStreamFactoryTest {
    private static final String QUEUE_KEY = "test-factory-stream";

    @Before
    public void setUp() {
        RedisConnectionManager.init("localhost", 6379, null, 0);
    }

    @Test
    public void testCreateMemoryStream() {
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
        assertEquals(QUEUE_KEY, stream.getName());
    }

    @Test
    public void testCreateRedisStream() {
        MessageStream<String> stream = MessageStreamFactory.create("redis", QUEUE_KEY, String.class);
        assertNotNull(stream);
        assertTrue(stream instanceof LettuceRedisStream);
    }

    @Test
    public void testCreateMemoryStreamWithParams() {
        Map<String, String> params = new HashMap<>();
        params.put("group", "test-group");
        params.put("consumerName", "test-consumer");
        MessageStream<TestPayload> stream = MessageStreamFactory.create("memory", QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }

    @Test
    public void testCreateRedisStreamWithParams() {
        Map<String, String> params = new HashMap<>();
        params.put("group", "test-group");
        params.put("consumerName", "test-consumer");
        MessageStream<TestPayload> stream = MessageStreamFactory.create("redis", QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof LettuceRedisStream);
    }

    @Test
    public void testCaseInsensitive() {
        MessageStream<String> stream1 = MessageStreamFactory.create("MEMORY", QUEUE_KEY, String.class);
        assertTrue(stream1 instanceof InMemoryMessageStream);

        MessageStream<String> stream2 = MessageStreamFactory.create("Redis", QUEUE_KEY, String.class);
        assertTrue(stream2 instanceof LettuceRedisStream);

        MessageStream<String> stream3 = MessageStreamFactory.create("rEdIs", QUEUE_KEY, String.class);
        assertTrue(stream3 instanceof LettuceRedisStream);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownStreamType() {
        MessageStreamFactory.create("kafka", QUEUE_KEY, String.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullStreamType() {
        MessageStreamFactory.create(null, QUEUE_KEY, String.class);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptyStreamType() {
        MessageStreamFactory.create("", QUEUE_KEY, String.class);
    }

    @Test
    public void testCreateWithNullParams() {
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class, null);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }

    @Test
    public void testCreateWithEmptyParams() {
        Map<String, String> params = new HashMap<>();
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class, params);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }
} 