package com.xa.mass.base.channel.queue.example;

import com.xa.mass.base.channel.queue.api.MessageStream;
import com.xa.mass.base.channel.queue.redis.LettuceRedisStream;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis Stream完整示例
 * 展示生产者-消费者模式、并发处理、错误恢复等完整功能
 */
public class RedisStreamCompleteExample {
    
    private static final String STREAM_KEY = "complete-demo";
    private static final String STREAM_NAME = "complete-stream";
    private static final int PRODUCER_COUNT = 2;
    private static final int CONSUMER_COUNT = 3;
    private static final int MESSAGE_COUNT = 20;
    
    public static void main(String[] args) {
        try {
            // 初始化Redis连接
            RedisConnectionManager.init("localhost", 6379, null, 0);
            System.out.println("=== Redis Stream 完整示例 ===");
            System.out.println("Redis连接初始化成功");
            
            // 创建消息流
            MessageStream<String> stream = new LettuceRedisStream<>(STREAM_KEY, STREAM_NAME, String.class);
            System.out.println("创建消息流: " + stream.getName());
            
            // 启动生产者和消费者
            runProducerConsumerDemo(stream);
            
        } catch (Exception e) {
            System.err.println("Redis Stream完整示例失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 运行生产者-消费者演示
     */
    private static void runProducerConsumerDemo(MessageStream<String> stream) throws InterruptedException {
        System.out.println("\n=== 生产者-消费者演示 ===");
        System.out.println("生产者数量: " + PRODUCER_COUNT);
        System.out.println("消费者数量: " + CONSUMER_COUNT);
        System.out.println("消息数量: " + MESSAGE_COUNT);
        
        CountDownLatch producerLatch = new CountDownLatch(PRODUCER_COUNT);
        CountDownLatch consumerLatch = new CountDownLatch(CONSUMER_COUNT);
        AtomicInteger producedCount = new AtomicInteger(0);
        AtomicInteger consumedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        // 启动生产者
        for (int i = 0; i < PRODUCER_COUNT; i++) {
            final int producerId = i + 1;
            new Thread(() -> {
                try {
                    producer(stream, producerId, MESSAGE_COUNT / PRODUCER_COUNT, producedCount);
                } finally {
                    producerLatch.countDown();
                }
            }, "Producer-" + producerId).start();
        }
        
        // 启动消费者
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            final int consumerId = i + 1;
            new Thread(() -> {
                try {
                    consumer(stream, consumerId, consumedCount, successCount, failureCount);
                } finally {
                    consumerLatch.countDown();
                }
            }, "Consumer-" + consumerId).start();
        }
        
        // 等待生产者完成
        producerLatch.await();
        System.out.println("\n所有生产者完成，等待消费者处理...");
        
        // 等待消费者完成（最多等待30秒）
        boolean completed = consumerLatch.await(30, TimeUnit.SECONDS);
        
        // 输出统计结果
        System.out.println("\n=== 处理结果统计 ===");
        System.out.println("投递消息数: " + producedCount.get());
        System.out.println("消费消息数: " + consumedCount.get());
        System.out.println("成功处理数: " + successCount.get());
        System.out.println("失败处理数: " + failureCount.get());
        System.out.println("剩余Stream大小: " + stream.size());
        System.out.println("消费者完成状态: " + (completed ? "正常完成" : "超时"));
        
        // 清理过期消息
        int cleaned = stream.cleanupExpiredMessages();
        System.out.println("清理过期消息数: " + cleaned);
    }
    
    /**
     * 生产者逻辑
     */
    private static void producer(MessageStream<String> stream, int producerId, int messageCount, AtomicInteger producedCount) {
        System.out.println("生产者 " + producerId + " 开始工作");
        
        try {
            for (int i = 1; i <= messageCount; i++) {
                String message = String.format("Producer-%d-Message-%d", producerId, i);
                String messageId = stream.offer(message);
                
                producedCount.incrementAndGet();
                System.out.println("生产者 " + producerId + " 投递消息: " + messageId + " -> " + message);
                
                // 模拟生产间隔
                Thread.sleep(100 + (int)(Math.random() * 200));
            }
            
            System.out.println("生产者 " + producerId + " 完成工作，共投递 " + messageCount + " 条消息");
            
        } catch (Exception e) {
            System.err.println("生产者 " + producerId + " 发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 消费者逻辑
     */
    private static void consumer(MessageStream<String> stream, int consumerId, AtomicInteger consumedCount, 
                                AtomicInteger successCount, AtomicInteger failureCount) {
        System.out.println("消费者 " + consumerId + " 开始工作");
        
        try {
            while (true) {
                // 消费消息，超时时间2秒
                MessageStream.StreamMessage<String> msg = stream.poll(2, TimeUnit.SECONDS);
                
                if (msg != null) {
                    consumedCount.incrementAndGet();
                    System.out.println("消费者 " + consumerId + " 消费消息: " + msg.getMessageId() + " -> " + msg.getMessage());
                    
                    // 模拟处理逻辑
                    boolean processSuccess = processMessage(msg.getMessage(), consumerId);
                    
                    if (processSuccess) {
                        // 处理成功，确认消息
                        boolean ackResult = stream.ack(msg.getMessageId());
                        if (ackResult) {
                            successCount.incrementAndGet();
                            System.out.println("消费者 " + consumerId + " 成功处理并确认消息: " + msg.getMessage());
                        } else {
                            failureCount.incrementAndGet();
                            System.out.println("消费者 " + consumerId + " 确认消息失败: " + msg.getMessage());
                        }
                    } else {
                        // 处理失败，删除消息（简化处理）
                        stream.ack(msg.getMessageId());
                        failureCount.incrementAndGet();
                        System.out.println("消费者 " + consumerId + " 处理失败，删除消息: " + msg.getMessage());
                    }
                    
                    // 模拟处理时间
                    Thread.sleep(50 + (int)(Math.random() * 150));
                    
                } else {
                    // 超时，检查是否还有消息
                    if (stream.size() == 0) {
                        System.out.println("消费者 " + consumerId + " 检测到Stream为空，退出工作");
                        break;
                    }
                }
            }
            
            System.out.println("消费者 " + consumerId + " 完成工作");
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("消费者 " + consumerId + " 被中断: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("消费者 " + consumerId + " 发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 模拟消息处理逻辑
     */
    private static boolean processMessage(String message, int consumerId) {
        // 模拟处理逻辑：基于消息内容和消费者ID的处理结果
        boolean success = true;
        
        // 模拟一些失败情况
        if (message.contains("Message-3") && consumerId == 1) {
            success = false; // 消费者1处理Message-3时失败
        } else if (message.contains("Message-7") && consumerId == 2) {
            success = false; // 消费者2处理Message-7时失败
        }
        
        // 模拟处理时间
        try {
            Thread.sleep(20 + (int)(Math.random() * 80));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return success;
    }
} 