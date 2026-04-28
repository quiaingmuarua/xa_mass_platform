package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.test.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LettuceRedisStreamTest {
    private static final String STREAM_KEY = "test-redis-stream";

    private LettuceRedisStream<String> stream;

    @BeforeEach
    public void setUp() {
        RedisTestSupport.initLocalRedisOrSkip();
        LettuceRedisStream<String> tmp = new LettuceRedisStream<>(STREAM_KEY, String.class);
        tmp.clear();
        stream = new LettuceRedisStream<>(STREAM_KEY, String.class);
        stream.ensureConsumerGroup();
    }

    @AfterEach
    public void tearDown() {
        if (stream == null) {
            return;
        }
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
        assertEquals(0, stream.processingSize());

        var m1 = stream.poll(1, TimeUnit.SECONDS);
        assertEquals(1, stream.processingSize());

        var m2 = stream.poll(1, TimeUnit.SECONDS);
        assertEquals(2, stream.processingSize());

        stream.ack(m1.getMessageId());
        assertEquals(1, stream.processingSize());

        stream.ack(m2.getMessageId());
        assertEquals(0, stream.processingSize());
    }

    @Test
    public void testAck() throws Exception {
        String msg = "ack-test";
        stream.offer(msg);
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
