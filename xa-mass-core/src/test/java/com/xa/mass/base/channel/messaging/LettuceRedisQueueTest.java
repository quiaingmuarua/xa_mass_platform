package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.redis.LettuceRedisQueue;
import com.xa.mass.base.test.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LettuceRedisQueueTest {
    private static final String QUEUE_KEY = "test-redis-queue";

    private LettuceRedisQueue<String> queue;

    @BeforeEach
    public void setUp() {
        RedisTestSupport.initLocalRedisOrSkip();
        queue = new LettuceRedisQueue<>(QUEUE_KEY, String.class);
        drainQueue();
    }

    @AfterEach
    public void tearDown() {
        if (queue == null) {
            return;
        }
        drainQueue();
    }

    @Test
    public void testOfferAndPoll() throws Exception {
        String msg = "hello-redis";
        queue.offer(msg);
        String result = queue.poll(2, TimeUnit.SECONDS);
        assertEquals(msg, result);
    }

    @Test
    public void testIsEmptyAndSize() throws Exception {
        assertTrue(queue.isEmpty());
        queue.offer("a");
        queue.offer("b");
        assertFalse(queue.isEmpty());
        assertEquals(2, queue.size());
        queue.poll(1, TimeUnit.SECONDS);
        assertEquals(1, queue.size());
        queue.poll(1, TimeUnit.SECONDS);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void testPollTimeout() throws Exception {
        String result = queue.poll(1, TimeUnit.SECONDS);
        assertNull(result);
    }

    private void drainQueue() {
        while (!queue.isEmpty()) {
            try {
                queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
