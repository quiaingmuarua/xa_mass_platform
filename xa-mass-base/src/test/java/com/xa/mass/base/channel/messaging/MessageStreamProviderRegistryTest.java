package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.tool.RedisConnectionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class MessageStreamProviderRegistryTest {
    private static final String QUEUE_KEY = "test-provider-registry";
    private static final String GROUP = "test-group";
    private static final String CONSUMER = "test-consumer";

    @Before
    public void setUp() {
        RedisConnectionManager.init("localhost", 6379, null, 0);
        MessageStreamProviderRegistry.clearCache();
    }

    @After
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
        MessageStreamProviderRegistry.register(MessageProviderType.valueOf("CUSTOM"),
                (queueKey, messageType, extraParams) -> new InMemoryMessageStream<>(queueKey, messageType));
        MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.valueOf("CUSTOM"), QUEUE_KEY, String.class, null);
        assertNotNull(stream);
        assertTrue(stream instanceof InMemoryMessageStream);
    }
} 