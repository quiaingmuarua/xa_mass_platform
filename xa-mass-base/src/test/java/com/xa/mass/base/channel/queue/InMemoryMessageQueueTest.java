package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

/**
 * InMemoryMessageQueue 测试类
 */
public class InMemoryMessageQueueTest {

    private InMemoryMessageQueue<String> messageQueue;

    @BeforeEach
    void setUp() {
        messageQueue = new InMemoryMessageQueue<>("test-queue", String.class);
    }

    @Test
    void testOfferAndPoll() throws InterruptedException {
        // 测试基本的 offer 和 poll 操作
        assertTrue(messageQueue.isEmpty());
        assertEquals(0, messageQueue.size());

        messageQueue.offer("message1");
        messageQueue.offer("message2");
        messageQueue.offer("message3");

        assertEquals(3, messageQueue.size());
        assertFalse(messageQueue.isEmpty());

        assertEquals("message1", messageQueue.poll(1, TimeUnit.SECONDS));
        assertEquals("message2", messageQueue.poll(1, TimeUnit.SECONDS));
        assertEquals("message3", messageQueue.poll(1, TimeUnit.SECONDS));

        assertEquals(0, messageQueue.size());
        assertTrue(messageQueue.isEmpty());
    }

    @Test
    void testTake() throws InterruptedException {
        // 测试 take 操作
        messageQueue.offer("message1");
        messageQueue.offer("message2");

        assertEquals("message1", messageQueue.take());
        assertEquals("message2", messageQueue.take());
        assertEquals(0, messageQueue.size());
    }

    @Test
    void testPollTimeout() throws InterruptedException {
        // 测试 poll 超时
        assertNull(messageQueue.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testIsEmpty() {
        // 测试 isEmpty 操作
        assertTrue(messageQueue.isEmpty());

        messageQueue.offer("message1");
        assertFalse(messageQueue.isEmpty());

        try {
            messageQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(messageQueue.isEmpty());
    }

    @Test
    void testSize() {
        // 测试 size 操作
        assertEquals(0, messageQueue.size());

        messageQueue.offer("message1");
        assertEquals(1, messageQueue.size());

        messageQueue.offer("message2");
        assertEquals(2, messageQueue.size());

        try {
            messageQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, messageQueue.size());
    }

    @Test
    void testGetName() {
        // 测试 getName 操作
        assertEquals("test-queue", messageQueue.getName());
    }

    @Test
    void testConcurrentOperations() throws InterruptedException {
        // 测试并发操作
        int threadCount = 10;
        int operationsPerThread = 50;
        Thread[] producerThreads = new Thread[threadCount];
        Thread[] consumerThreads = new Thread[threadCount];

        // 创建生产者线程
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            producerThreads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    messageQueue.offer("message_" + threadId + "_" + j);
                }
            });
        }

        // 创建消费者线程
        for (int i = 0; i < threadCount; i++) {
            consumerThreads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        String message = messageQueue.poll(1, TimeUnit.SECONDS);
                        assertNotNull(message);
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
        assertEquals(0, messageQueue.size());
        assertTrue(messageQueue.isEmpty());
    }

    @Test
    void testNullMessage() {
        // 测试 null 消息处理
        assertThrows(IllegalArgumentException.class, () -> {
            messageQueue.offer(null);
        });
    }

    @Test
    void testLargeNumberOfMessages() throws InterruptedException {
        // 测试大量消息
        int messageCount = 10000;
        
        // 生产消息
        for (int i = 0; i < messageCount; i++) {
            messageQueue.offer("message_" + i);
        }
        
        assertEquals(messageCount, messageQueue.size());
        
        // 消费消息
        for (int i = 0; i < messageCount; i++) {
            String message = messageQueue.poll(1, TimeUnit.SECONDS);
            assertEquals("message_" + i, message);
        }
        
        assertEquals(0, messageQueue.size());
        assertTrue(messageQueue.isEmpty());
    }

    @Test
    void testInterruptedPoll() throws InterruptedException {
        // 测试中断的 poll 操作
        Thread testThread = new Thread(() -> {
            try {
                messageQueue.poll(10, TimeUnit.SECONDS);
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // 预期的中断
                Thread.currentThread().interrupt();
            }
        });

        testThread.start();
        Thread.sleep(100); // 等待线程开始执行
        testThread.interrupt();
        testThread.join();
    }

    @Test
    void testInterruptedTake() throws InterruptedException {
        // 测试中断的 take 操作
        Thread testThread = new Thread(() -> {
            try {
                messageQueue.take();
                fail("Should have been interrupted");
            } catch (InterruptedException e) {
                // 预期的中断
                Thread.currentThread().interrupt();
            }
        });

        testThread.start();
        Thread.sleep(100); // 等待线程开始执行
        testThread.interrupt();
        testThread.join();
    }
} 