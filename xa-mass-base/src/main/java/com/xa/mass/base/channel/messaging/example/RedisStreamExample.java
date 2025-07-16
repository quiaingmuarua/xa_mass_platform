package com.xa.mass.base.channel.messaging.example;

import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.MessageStreamProviderRegistry;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;
import com.xa.mass.base.tool.RedisConnectionManager;

import java.util.concurrent.TimeUnit;

/**
 * Redis Stream使用示例
 * 展示Redis Stream的基本使用、ACK功能和错误处理
 */
public class RedisStreamExample {
    
    public static void main(String[] args) {
        // 示例1: 基本使用
        basicUsage();
        
        // 示例2: 批量消息处理
        batchProcessing();
        
        // 示例3: 错误处理和重试
        errorHandling();
        
        // 示例4: 通过注册表创建
        registryUsage();
    }
    
    /**
     * 示例1: 基本使用
     */
    private static void basicUsage() {
        System.out.println("=== 示例1: Redis Stream基本使用 ===");
        
        try {
            // 初始化Redis连接
            RedisConnectionManager.init("localhost", 6379, null, 0);
            System.out.println("Redis连接初始化成功");
            
            // 创建Redis Stream
            MessageStream<String> stream = new LettuceRedisStream<>("redis-stream-demo", String.class, java.util.Collections.emptyMap());
            
            System.out.println("Redis Stream名称: " + stream.getName());
            System.out.println("Redis Stream键名: " + ((LettuceRedisStream<String>) stream).getStreamKey());
            
            // 投递消息
            String messageId1 = stream.offer("Hello Redis Stream!");
            String messageId2 = stream.offer("这是Redis Stream的第二条消息");
            String messageId3 = stream.offer("Redis Stream支持可靠消息处理");
            
            System.out.println("投递消息1: " + messageId1);
            System.out.println("投递消息2: " + messageId2);
            System.out.println("投递消息3: " + messageId3);
            System.out.println("Stream大小: " + stream.size());
            
            // 消费消息
            try {
                for (int i = 0; i < 3; i++) {
                    MessageStream.StreamMessage<String> msg = stream.poll(5, TimeUnit.SECONDS);
                    if (msg != null) {
                        System.out.println("消费消息" + (i + 1) + ": ID=" + msg.getMessageId() + 
                                         ", 内容=" + msg.getMessage() + 
                                         ", 时间戳=" + msg.getTimestamp());
                        
                        // 模拟处理时间
                        Thread.sleep(100);
                        
                        // 确认消息
                        boolean ackResult = stream.ack(msg.getMessageId());
                        System.out.println("确认消息" + (i + 1) + ": " + (ackResult ? "成功" : "失败"));
                    }
                }
                
                System.out.println("消费后Stream大小: " + stream.size());
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("Redis Stream操作失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例2: 批量消息处理
     */
    private static void batchProcessing() {
        System.out.println("\n=== 示例2: Redis Stream批量消息处理 ===");
        
        try {
            // 创建Redis Stream
            MessageStream<Integer> stream = new LettuceRedisStream<>("redis-int-stream", Integer.class, java.util.Collections.emptyMap());
            
            // 批量投递消息
            System.out.println("开始批量投递消息...");
            int messageCount = 10;
            String[] messageIds = new String[messageCount];
            
            for (int i = 1; i <= messageCount; i++) {
                messageIds[i - 1] = stream.offer(i);
                if (i % 5 == 0) {
                    System.out.println("已投递 " + i + " 条消息");
                }
            }
            
            System.out.println("批量投递完成，Stream大小: " + stream.size());
            
            // 批量消费和处理
            System.out.println("开始批量消费消息...");
            int processedCount = 0;
            int successCount = 0;
            
            try {
                while (processedCount < messageCount) {
                    MessageStream.StreamMessage<Integer> msg = stream.poll(2, TimeUnit.SECONDS);
                    if (msg != null) {
                        processedCount++;
                        
                        // 模拟处理逻辑
                        boolean processSuccess = processMessage(msg.getMessage());
                        
                        if (processSuccess) {
                            // 处理成功，确认消息
                            boolean ackResult = stream.ack(msg.getMessageId());
                            if (ackResult) {
                                successCount++;
                                System.out.println("成功处理消息: " + msg.getMessage());
                            } else {
                                System.out.println("确认消息失败: " + msg.getMessage());
                            }
                        } else {
                            // 处理失败，尝试认领（简化版本不支持，直接删除）
                            System.out.println("处理失败，跳过消息: " + msg.getMessage());
                            stream.ack(msg.getMessageId()); // 简化处理
                        }
                        
                        // 模拟处理时间
                        Thread.sleep(50);
                    } else {
                        System.out.println("等待超时，已处理 " + processedCount + " 条消息");
                        break;
                    }
                }
                
                System.out.println("批量处理完成:");
                System.out.println("- 总处理数: " + processedCount);
                System.out.println("- 成功数: " + successCount);
                System.out.println("- 失败数: " + (processedCount - successCount));
                System.out.println("- 剩余Stream大小: " + stream.size());
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("批量处理时被中断: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("批量处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例3: 错误处理和重试
     */
    private static void errorHandling() {
        System.out.println("\n=== 示例3: Redis Stream错误处理和重试 ===");
        
        try {
            // 创建Redis Stream
            MessageStream<String> stream = new LettuceRedisStream<>("redis-claim-demo", String.class, java.util.Collections.emptyMap());
            
            // 投递测试消息
            String messageId = stream.offer("需要重试处理的消息");
            System.out.println("投递测试消息: " + messageId);
            
            try {
                // 第一次消费
                MessageStream.StreamMessage<String> msg = stream.poll(5, TimeUnit.SECONDS);
                if (msg != null) {
                    System.out.println("第一次消费消息: " + msg.getMessage());
                    
                    // 模拟第一次处理失败
                    boolean firstProcessSuccess = false;
                    System.out.println("第一次处理: " + (firstProcessSuccess ? "成功" : "失败"));
                    
                    if (!firstProcessSuccess) {
                        // 尝试认领消息（简化版本不支持，直接重新投递）
                        System.out.println("处理失败，尝试重新投递...");
                        
                        // 删除原消息
                        stream.ack(msg.getMessageId());
                        
                        // 重新投递
                        String newMessageId = stream.offer(msg.getMessage() + " (重试)");
                        System.out.println("重新投递消息: " + newMessageId);
                        
                        // 第二次消费
                        MessageStream.StreamMessage<String> retryMsg = stream.poll(5, TimeUnit.SECONDS);
                        if (retryMsg != null) {
                            System.out.println("第二次消费消息: " + retryMsg.getMessage());
                            
                            // 模拟第二次处理成功
                            boolean secondProcessSuccess = true;
                            System.out.println("第二次处理: " + (secondProcessSuccess ? "成功" : "失败"));
                            
                            if (secondProcessSuccess) {
                                boolean ackResult = stream.ack(retryMsg.getMessageId());
                                System.out.println("确认重试消息: " + (ackResult ? "成功" : "失败"));
                            }
                        }
                    } else {
                        // 第一次就成功
                        stream.ack(msg.getMessageId());
                        System.out.println("第一次处理成功，确认消息");
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("错误处理时被中断: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("错误处理示例失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 示例4: 通过注册表创建
     */
    private static void registryUsage() {
        System.out.println("\n=== 示例4: 通过注册表创建Redis Stream ===");
        
        try {
            // 通过注册表创建Redis Stream
            MessageStream<String> stream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.REDIS, "registry-redis-stream", String.class, java.util.Collections.emptyMap()
            );
            
            System.out.println("通过注册表创建的Redis Stream: " + stream.getName());
            
            // 基本操作
            String messageId = stream.offer("注册表创建的Redis Stream消息");
            System.out.println("投递消息: " + messageId);
            
            try {
                MessageStream.StreamMessage<String> msg = stream.poll(5, TimeUnit.SECONDS);
                if (msg != null) {
                    System.out.println("消费消息: " + msg.getMessage());
                    
                    boolean ackResult = stream.ack(msg.getMessageId());
                    System.out.println("确认消息: " + (ackResult ? "成功" : "失败"));
                    
                    System.out.println("操作完成，Stream大小: " + stream.size());
                } else {
                    System.out.println("未收到消息");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("注册表创建Redis Stream失败: " + e.getMessage());
            System.err.println("可能原因: Redis未启动或连接失败");
            e.printStackTrace();
        }
    }
    
    /**
     * 模拟消息处理逻辑
     * @param message 消息内容
     * @return 处理是否成功
     */
    private static boolean processMessage(Integer message) {
        // 模拟处理逻辑：偶数成功，奇数失败
        boolean success = message % 2 == 0;
        
        // 模拟处理时间
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return success;
    }
} 