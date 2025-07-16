package com.xa.mass.base.channel.messaging.example;

import com.xa.mass.base.channel.messaging.redis.LettuceRedisQueue;
import com.xa.mass.base.tool.RedisConnectionManager;
import java.util.concurrent.TimeUnit;

/**
 * Lettuce队列使用示例
 * 展示单连接多路复用的优势
 */
public class LettuceQueueExample {
    
    public static void main(String[] args) {
        // 推荐：先初始化全局Redis连接
        RedisConnectionManager.init("localhost", 6379, null, 0);

        // 示例1: 基本使用
        basicUsage();
        
        // 示例2: 性能测试
        performanceComparison();
        
        // 示例3: 多队列共享连接
        multipleQueuesWithSingleConnection();
    }
    
    /**
     * 示例1: 基本使用
     */
    private static void basicUsage() {
        System.out.println("=== 示例1: Lettuce队列基本使用 ===");
        
        // 创建Lettuce队列（单连接，无需连接池）
        LettuceRedisQueue<String> queue = new LettuceRedisQueue<>(
            "queue:lettuce-example",  // Redis键名
            String.class              // 消息类型
        );
        
        try {
            System.out.println("队列名称: " + queue.getName());
            System.out.println("队列键名: " + queue.getQueueKey());
            // 发送消息
            queue.offer("Hello Lettuce!");
            queue.offer("这是Lettuce队列的消息");
            
            System.out.println("队列大小: " + queue.size());
            
            // 接收消息
            String message1 = null;
            String message2 = null;
            try {
                message1 = queue.poll(5, TimeUnit.SECONDS);
                message2 = queue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("接收消息时被中断: " + e.getMessage());
            }
            
            System.out.println("接收到的消息1: " + message1);
            System.out.println("接收到的消息2: " + message2);
            System.out.println("队列大小: " + queue.size());
            
        } finally {
            // 不再需要单独关闭队列，连接由RedisConnectionManager统一管理
        }
    }
    
    /**
     * 示例2: 性能测试演示
     */
    private static void performanceComparison() {
        System.out.println("\n=== 示例2: Lettuce性能测试演示 ===");
        
        // Lettuce队列（单连接多路复用）
        LettuceRedisQueue<Integer> queue = new LettuceRedisQueue<>(
            "queue:lettuce-perf", Integer.class
        );
        
        try {
            // 性能测试：发送1000条消息
            int messageCount = 1000;
            
            System.out.println("发送 " + messageCount + " 条消息...");
            
            long start = System.currentTimeMillis();
            for (int i = 0; i < messageCount; i++) {
                queue.offer(i);
            }
            long end = System.currentTimeMillis();
            
            System.out.println("Lettuce发送耗时: " + (end - start) + "ms");
            System.out.println("平均每条消息发送时间: " + ((double)(end - start) / messageCount) + "ms");
            
            // 接收消息测试
            System.out.println("接收 " + messageCount + " 条消息...");
            
            long recvStart = System.currentTimeMillis();
            for (int i = 0; i < messageCount; i++) {
                try {
                    Integer msg = queue.poll(1, TimeUnit.SECONDS);
                    if (msg == null) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long recvEnd = System.currentTimeMillis();
            
            System.out.println("Lettuce接收耗时: " + (recvEnd - recvStart) + "ms");
            System.out.println("平均每条消息接收时间: " + ((double)(recvEnd - recvStart) / messageCount) + "ms");
            
        } finally {
        }
    }
    
    /**
     * 示例3: 多队列共享连接
     */
    private static void multipleQueuesWithSingleConnection() {
        System.out.println("\n=== 示例3: 多队列共享连接 ===");
        
        // 创建多个Lettuce队列（共享同一个Redis客户端连接）
        LettuceRedisQueue<String> stringQueue = new LettuceRedisQueue<>(
            "queue:strings", String.class
        );
        
        LettuceRedisQueue<Integer> numberQueue = new LettuceRedisQueue<>(
            "queue:numbers", Integer.class
        );
        
        LettuceRedisQueue<Double> doubleQueue = new LettuceRedisQueue<>(
            "queue:doubles", Double.class
        );
        
        try {
            // 向不同队列发送不同类型的消息
            stringQueue.offer("字符串消息");
            numberQueue.offer(42);
            doubleQueue.offer(3.14159);
            
            System.out.println("字符串队列大小: " + stringQueue.size());
            System.out.println("数字队列大小: " + numberQueue.size());
            System.out.println("浮点队列大小: " + doubleQueue.size());
            
            // 从不同队列接收消息
            try {
                String strMsg = stringQueue.poll(5, TimeUnit.SECONDS);
                Integer numMsg = numberQueue.poll(5, TimeUnit.SECONDS);
                Double doubleMsg = doubleQueue.poll(5, TimeUnit.SECONDS);
                
                System.out.println("字符串消息: " + strMsg);
                System.out.println("数字消息: " + numMsg);
                System.out.println("浮点消息: " + doubleMsg);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("接收消息时被中断: " + e.getMessage());
            }
            
        } finally {
        }
    }
} 