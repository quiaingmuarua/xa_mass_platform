package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.tool.RedisConnectionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LettuceRedisStreamTest {
    private LettuceRedisStream<String> stream;
    private static final String STREAM_KEY = "test-redis-stream";

    @Before
    public void setUp() {
        RedisConnectionManager.init("localhost", 6379, null, 0);
        stream = new LettuceRedisStream<>(STREAM_KEY, String.class);
        stream.clear();
    }

    @After
    public void tearDown() {
        stream.clear();
    }

    @Test
    public void testOfferAndPoll() throws Exception {
        String msg = "hello-redis-stream";
        stream.offer(msg);
        String result = stream.poll(2, TimeUnit.SECONDS).getMessage();
        assertEquals(msg, result);
    }

    @Test
    public void testIsEmptyAndSize() throws Exception {
        assertTrue(stream.isEmpty());
        stream.offer("a");
        stream.offer("b");
        assertFalse(stream.isEmpty());
        assertEquals(2, stream.size());
        stream.poll(1, TimeUnit.SECONDS);
        assertEquals(1, stream.size());
        stream.poll(1, TimeUnit.SECONDS);
        assertTrue(stream.isEmpty());
    }

    @Test
    public void testAck() throws Exception {
        String msg = "ack-test";
        String id = stream.offer(msg);
        var polled = stream.poll(2, TimeUnit.SECONDS);
        assertNotNull(polled);
        assertEquals(msg, polled.getMessage());
        boolean acked = stream.ack(polled.getMessageId());
        assertTrue(acked);
    }

    @Test
    public void testBatchOfferAndPoll() throws Exception {
        List<String> msgs = List.of("m1", "m2", "m3");
        stream.offerBatch(msgs);
        List<String> results = new ArrayList<>();
        for (int i = 0; i < msgs.size(); i++) {
            var polled = stream.poll(2, TimeUnit.SECONDS);
            assertNotNull(polled);
            results.add(polled.getMessage());
        }
        assertEquals(msgs, results);
    }

    @Test
    public void testPollTimeout() throws Exception {
        var result = stream.poll(1, TimeUnit.SECONDS);
        assertNull(result);
    }
} 