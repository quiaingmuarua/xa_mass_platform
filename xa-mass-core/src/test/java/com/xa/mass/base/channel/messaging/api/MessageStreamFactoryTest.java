package com.xa.mass.base.channel.messaging.api;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeEach
    public void setUp() {
        RedisConnectionManager.init("localhost", 6379, null, 0);
    }

    @Test
    public void testCreateMemoryStream() {
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class);
        assertNotNull(stream);
        assertInstanceOf(InMemoryMessageStream.class, stream);
        assertEquals(QUEUE_KEY, stream.getName());
    }

    @Test
    public void testCreateRedisStream() {
        MessageStream<String> stream = MessageStreamFactory.create("redis", QUEUE_KEY, String.class);
        assertNotNull(stream);
        assertInstanceOf(LettuceRedisStream.class, stream);
    }

    @Test
    public void testCreateMemoryStreamWithParams() {
        Map<String, String> params = new HashMap<>();
        params.put("group", "test-group");
        params.put("consumerName", "test-consumer");
        MessageStream<TestPayload> stream = MessageStreamFactory.create("memory", QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertInstanceOf(InMemoryMessageStream.class, stream);
    }

    @Test
    public void testCreateRedisStreamWithParams() {
        Map<String, String> params = new HashMap<>();
        params.put("group", "test-group");
        params.put("consumerName", "test-consumer");
        MessageStream<TestPayload> stream = MessageStreamFactory.create("redis", QUEUE_KEY, TestPayload.class, params);
        assertNotNull(stream);
        assertInstanceOf(LettuceRedisStream.class, stream);
    }

    @Test
    public void testCaseInsensitive() {
        assertInstanceOf(InMemoryMessageStream.class, MessageStreamFactory.create("MEMORY", QUEUE_KEY, String.class));
        assertInstanceOf(LettuceRedisStream.class, MessageStreamFactory.create("Redis", QUEUE_KEY, String.class));
        assertInstanceOf(LettuceRedisStream.class, MessageStreamFactory.create("rEdIs", QUEUE_KEY, String.class));
    }

    @Test
    public void testUnknownStreamType() {
        assertThrows(IllegalArgumentException.class,
                () -> MessageStreamFactory.create("kafka", QUEUE_KEY, String.class));
    }

    @Test
    public void testNullStreamType() {
        assertThrows(IllegalArgumentException.class,
                () -> MessageStreamFactory.create(null, QUEUE_KEY, String.class));
    }

    @Test
    public void testEmptyStreamType() {
        assertThrows(IllegalArgumentException.class,
                () -> MessageStreamFactory.create("", QUEUE_KEY, String.class));
    }

    @Test
    public void testCreateWithNullParams() {
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class, null);
        assertNotNull(stream);
        assertInstanceOf(InMemoryMessageStream.class, stream);
    }

    @Test
    public void testCreateWithEmptyParams() {
        Map<String, String> params = new HashMap<>();
        MessageStream<String> stream = MessageStreamFactory.create("memory", QUEUE_KEY, String.class, params);
        assertNotNull(stream);
        assertInstanceOf(InMemoryMessageStream.class, stream);
    }
}
