package com.xa.mass.base.channel.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * InMemoryMessageSet 测试类
 */
public class InMemoryMessageSetTest {

    private InMemoryMessageSet<String> messageSet;

    @BeforeEach
    void setUp() {
        messageSet = new InMemoryMessageSet<>();
    }

    @Test
    void testAddAndContains() {
        assertTrue(messageSet.add("msg1"));
        assertTrue(messageSet.add("msg2"));
        assertTrue(messageSet.contains("msg1"));
        assertTrue(messageSet.contains("msg2"));
        assertFalse(messageSet.contains("msg3"));
    }

    @Test
    void testAddDuplicate() {
        assertTrue(messageSet.add("msg1"));
        assertFalse(messageSet.add("msg1")); // 不允许重复
        assertEquals(1, messageSet.size());
    }

    @Test
    void testRemove() {
        messageSet.add("msg1");
        messageSet.add("msg2");
        assertTrue(messageSet.remove("msg1"));
        assertFalse(messageSet.contains("msg1"));
        assertEquals(1, messageSet.size());
        assertFalse(messageSet.remove("msg3"));
    }

    @Test
    void testSize() {
        assertEquals(0, messageSet.size());
        messageSet.add("a");
        assertEquals(1, messageSet.size());
        messageSet.add("b");
        assertEquals(2, messageSet.size());
        messageSet.remove("a");
        assertEquals(1, messageSet.size());
    }

    @Test
    void testGetName() {
        assertEquals("InMemoryMessageSet", messageSet.getName());
        InMemoryMessageSet<String> customSet = new InMemoryMessageSet<>("CustomSet");
        assertEquals("CustomSet", customSet.getName());
    }

    @Test
    void testNullElement() {
        // 测试 null 元素 - 应该抛出异常
        assertThrows(NullPointerException.class, () -> messageSet.add(null));
        assertThrows(NullPointerException.class, () -> messageSet.contains(null));
        assertThrows(NullPointerException.class, () -> messageSet.remove(null));
    }

    @Test
    void testConcurrentAdd() throws InterruptedException {
        int threadCount = 10;
        int perThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        Set<String> all = new HashSet<>();
        for (int i = 0; i < threadCount; i++) {
            for (int j = 0; j < perThread; j++) {
                all.add("msg_" + i + "_" + j);
            }
        }
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    messageSet.add("msg_" + threadId + "_" + j);
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        assertEquals(all.size(), messageSet.size());
        for (String s : all) {
            assertTrue(messageSet.contains(s));
        }
    }

    @Test
    void testConcurrentRemove() throws InterruptedException {
        int threadCount = 5;
        int perThread = 50;
        for (int i = 0; i < threadCount * perThread; i++) {
            messageSet.add("msg_" + i);
        }
        assertEquals(threadCount * perThread, messageSet.size());
        CountDownLatch latch = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    messageSet.remove("msg_" + (threadId * perThread + j));
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        assertEquals(0, messageSet.size());
    }
} 