package com.xa.mass.base.channel.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

/**
 * InMemoryMessageQueueWithMap 测试类
 */
public class InMemoryMessageQueueWithMapTest {

    private InMemoryMessageQueueWithMap<String, Integer> queueWithMap;

    @BeforeEach
    void setUp() {
        queueWithMap = new InMemoryMessageQueueWithMap<>();
    }

    @Test
    void testQueueOperations() throws InterruptedException {
        // 测试队列基本操作
        assertTrue(queueWithMap.isEmpty());
        assertEquals(0, queueWithMap.size());

        // 测试 offer 操作
        queueWithMap.offer(100);
        queueWithMap.offer(200);
        queueWithMap.offer(300);

        assertEquals(3, queueWithMap.size());
        assertFalse(queueWithMap.isEmpty());

        // 测试 poll 操作
        assertEquals(100, queueWithMap.poll(1, TimeUnit.SECONDS));
        assertEquals(200, queueWithMap.poll(1, TimeUnit.SECONDS));
        assertEquals(300, queueWithMap.poll(1, TimeUnit.SECONDS));

        assertEquals(0, queueWithMap.size());
        assertTrue(queueWithMap.isEmpty());

        // 测试超时 poll
        assertNull(queueWithMap.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testMapOperations() {
        // 测试映射基本操作
        assertEquals(0, queueWithMap.getMapSize());

        // 测试 put 和 get 操作
        queueWithMap.put("key1", 100);
        queueWithMap.put("key2", 200);

        assertEquals(100, queueWithMap.get("key1"));
        assertEquals(200, queueWithMap.get("key2"));
        assertNull(queueWithMap.get("nonExistentKey"));
        assertEquals(2, queueWithMap.getMapSize());

        // 测试 containsKey 操作
        assertTrue(queueWithMap.containsKey("key1"));
        assertFalse(queueWithMap.containsKey("nonExistentKey"));

        // 测试 remove 操作
        assertEquals(100, queueWithMap.remove("key1"));
        assertNull(queueWithMap.get("key1"));
        assertFalse(queueWithMap.containsKey("key1"));
        assertEquals(1, queueWithMap.getMapSize());
    }

    @Test
    void testQueueAndMapIndependence() throws InterruptedException {
        // 测试队列和映射的独立性
        // 队列操作不影响映射
        queueWithMap.offer(100);
        queueWithMap.offer(200);
        assertEquals(2, queueWithMap.size());
        assertEquals(0, queueWithMap.getMapSize());

        // 映射操作不影响队列
        queueWithMap.put("key1", 300);
        queueWithMap.put("key2", 400);
        assertEquals(2, queueWithMap.size());
        assertEquals(2, queueWithMap.getMapSize());

        // 从队列取出元素不影响映射
        assertEquals(100, queueWithMap.poll(1, TimeUnit.SECONDS));
        assertEquals(1, queueWithMap.size());
        assertEquals(2, queueWithMap.getMapSize());
        assertEquals(300, queueWithMap.get("key1"));
    }

    @Test
    void testConcurrentQueueOperations() throws InterruptedException {
        // 测试队列并发操作
        int threadCount = 5;
        int operationsPerThread = 20;
        Thread[] producerThreads = new Thread[threadCount];
        Thread[] consumerThreads = new Thread[threadCount];

        // 创建生产者线程
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            producerThreads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    queueWithMap.offer(threadId * 1000 + j);
                }
            });
        }

        // 创建消费者线程
        for (int i = 0; i < threadCount; i++) {
            consumerThreads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        Integer value = queueWithMap.poll(1, TimeUnit.SECONDS);
                        assertNotNull(value);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // 启动生产者线程
        for (Thread thread : producerThreads) {
            thread.start();
        }

        // 等待生产者完成
        for (Thread thread : producerThreads) {
            thread.join();
        }

        // 启动消费者线程
        for (Thread thread : consumerThreads) {
            thread.start();
        }

        // 等待消费者完成
        for (Thread thread : consumerThreads) {
            thread.join();
        }

        // 验证队列为空
        assertEquals(0, queueWithMap.size());
        assertTrue(queueWithMap.isEmpty());
    }

    @Test
    void testConcurrentMapOperations() throws InterruptedException {
        // 测试映射并发操作
        int threadCount = 10;
        int operationsPerThread = 50;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    String key = "key_" + threadId + "_" + j;
                    Integer value = threadId * 1000 + j;
                    queueWithMap.put(key, value);
                    assertEquals(value, queueWithMap.get(key));
                }
            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证结果
        assertEquals(threadCount * operationsPerThread, queueWithMap.getMapSize());
    }

    @Test
    void testGetName() {
        // 测试 getName 操作
        assertEquals("InMemoryMessageQueueWithMap", queueWithMap.getName());

        InMemoryMessageQueueWithMap<String, Integer> customQueue = 
            new InMemoryMessageQueueWithMap<>("CustomQueueWithMap");
        assertEquals("CustomQueueWithMap", customQueue.getName());
    }

    @Test
    void testNullHandling() throws InterruptedException {
        // 队列中的 null 值 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> queueWithMap.offer(null));

        // 映射中的 null key 和 value - 应该抛出异常
        assertThrows(NullPointerException.class, () -> queueWithMap.put(null, 100));
        assertThrows(NullPointerException.class, () -> queueWithMap.get(null));
        assertThrows(NullPointerException.class, () -> queueWithMap.containsKey(null));
        assertThrows(NullPointerException.class, () -> queueWithMap.put("key1", null));
    }

    @Test
    void testMixedOperations() throws InterruptedException {
        // 测试混合操作场景
        // 同时进行队列和映射操作
        queueWithMap.offer(100);
        queueWithMap.put("key1", 200);
        queueWithMap.offer(300);
        queueWithMap.put("key2", 400);

        // 验证状态
        assertEquals(2, queueWithMap.size());
        assertEquals(2, queueWithMap.getMapSize());

        // 混合取出操作
        assertEquals(100, queueWithMap.poll(1, TimeUnit.SECONDS));
        assertEquals(200, queueWithMap.remove("key1"));

        // 验证最终状态
        assertEquals(1, queueWithMap.size());
        assertEquals(1, queueWithMap.getMapSize());
        assertEquals(300, queueWithMap.poll(1, TimeUnit.SECONDS));
        assertEquals(400, queueWithMap.get("key2"));
    }
} 