package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.redis.LettuceRedisQueue;
import com.xa.mass.base.channel.messaging.redis.RedisConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LettuceRedisQueueTest {
    private LettuceRedisQueue<String> queue;
    private static final String QUEUE_KEY = "test-redis-queue";

    @BeforeEach
    public void setUp() {
        // 确保Redis连接初始化
        RedisConnectionManager.init("localhost", 6379, null, 0);
        queue = new LettuceRedisQueue<>(QUEUE_KEY, String.class);
        // 清空队列，避免脏数据
        try {
            queue.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            queue.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        try {
            queue.poll(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    public void tearDown() {
        // 清空队列
        while (!queue.isEmpty()) {
            try {
                queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
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
} 