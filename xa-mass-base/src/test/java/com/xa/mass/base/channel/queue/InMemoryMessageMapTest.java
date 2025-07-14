package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryMessageMap 测试类
 */
public class InMemoryMessageMapTest {

    private InMemoryMessageMap<Integer> messageMap;

    @BeforeEach
    void setUp() {
        messageMap = new InMemoryMessageMap<>("test-map", Integer.class);
    }

    @Test
    void testPutAndGet() {
        // 测试基本的 put 和 get 操作
        messageMap.put("key1", 100);
        messageMap.put("key2", 200);

        assertEquals(100, messageMap.get("key1"));
        assertEquals(200, messageMap.get("key2"));
        assertNull(messageMap.get("nonExistentKey"));
    }

    @Test
    void testRemove() {
        // 测试 remove 操作
        messageMap.put("key1", 100);
        messageMap.put("key2", 200);

        assertEquals(100, messageMap.remove("key1"));
        assertNull(messageMap.get("key1"));
        assertEquals(200, messageMap.get("key2"));
        assertNull(messageMap.remove("nonExistentKey"));
    }

    @Test
    void testContainsKey() {
        // 测试 containsKey 操作
        messageMap.put("key1", 100);

        assertTrue(messageMap.containsKey("key1"));
        assertFalse(messageMap.containsKey("nonExistentKey"));
    }

    @Test
    void testSize() {
        // 测试 size 操作
        assertEquals(0, messageMap.size());

        messageMap.put("key1", 100);
        assertEquals(1, messageMap.size());

        messageMap.put("key2", 200);
        assertEquals(2, messageMap.size());

        messageMap.remove("key1");
        assertEquals(1, messageMap.size());
    }

    @Test
    void testGetName() {
        // 测试 getName 操作
        assertEquals("test-map", messageMap.getName());

        InMemoryMessageMap<Integer> customMap = new InMemoryMessageMap<>("CustomMap", Integer.class);
        assertEquals("CustomMap", customMap.getName());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // 测试并发操作
        int threadCount = 10;
        int operationsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    String key = "key_" + threadId + "_" + j;
                    Integer value = threadId * 1000 + j;
                    messageMap.put(key, value);
                    assertEquals(value, messageMap.get(key));
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
        assertEquals(threadCount * operationsPerThread, messageMap.size());
    }

    @Test
    void testNullKey() {
        // 测试 null key 的处理 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> messageMap.put(null, 100));
        assertThrows(NullPointerException.class, () -> messageMap.get(null));
        assertThrows(NullPointerException.class, () -> messageMap.containsKey(null));
        assertThrows(NullPointerException.class, () -> messageMap.remove(null));
    }

    @Test
    void testNullValue() {
        // 测试 null value 的处理 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> messageMap.put("key1", null));
    }
} 