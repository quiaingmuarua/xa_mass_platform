package com.xa.mass.base.channel.messaging.example;

import com.xa.mass.base.channel.messaging.MessageProviderType;
import com.xa.mass.base.channel.messaging.MessageStreamProviderRegistry;
import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;

import java.util.concurrent.TimeUnit;

/**
 * MessageStream使用示例
 * 展示消息流的基本使用、ACK和CLAIM功能
 */
public class MessageStreamExample {
    
    public static void main(String[] args) {
        // 示例1: 基本使用
        basicUsage();
        
        // 示例2: ACK功能演示
        ackDemo();
        
        // 示例3: CLAIM功能演示
        claimDemo();
        
        // 示例4: 通过注册表创建
        registryUsage();
    }
    
    /**
     * 示例1: 基本使用
     */
    private static void basicUsage() {
        System.out.println("=== 示例1: MessageStream基本使用 ===");
        
        // 创建内存消息流
        MessageStream<String> stream = new InMemoryMessageStream<>("test-stream", String.class);
        
        try {
            System.out.println("流名称: " + stream.getName());
            
            // 投递消息
            String messageId1 = stream.offer("Hello Stream!");
            String messageId2 = stream.offer("这是第二条消息");
            
            System.out.println("投递消息1: " + messageId1);
            System.out.println("投递消息2: " + messageId2);
            System.out.println("流大小: " + stream.size());
            
            // 消费消息
            try {
                MessageStream.StreamMessage<String> msg1 = stream.poll(5, TimeUnit.SECONDS);
                MessageStream.StreamMessage<String> msg2 = stream.poll(5, TimeUnit.SECONDS);
                
                if (msg1 != null) {
                    System.out.println("消费消息1: ID=" + msg1.getMessageId() + ", 内容=" + msg1.getMessage());
                }
                if (msg2 != null) {
                    System.out.println("消费消息2: ID=" + msg2.getMessageId() + ", 内容=" + msg2.getMessage());
                }
                
                System.out.println("消费后流大小: " + stream.size());
                System.out.println("处理中消息数量: " + stream.processingSize());
                
                // 确认消息
                if (msg1 != null) {
                    boolean ackResult = stream.ack(msg1.getMessageId());
                    System.out.println("确认消息1: " + (ackResult ? "成功" : "失败"));
                }
                if (msg2 != null) {
                    boolean ackResult = stream.ack(msg2.getMessageId());
                    System.out.println("确认消息2: " + (ackResult ? "成功" : "失败"));
                }
                
                System.out.println("确认后处理中消息数量: " + stream.processingSize());
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } finally {
            // 清理
            stream.cleanupExpiredMessages();
        }
    }
    
    /**
     * 示例2: ACK功能演示
     */
    private static void ackDemo() {
        System.out.println("\n=== 示例2: ACK功能演示 ===");
        
        MessageStream<Integer> stream = new InMemoryMessageStream<>("ack-demo", Integer.class);
        
        try {
            // 投递多个消息
            for (int i = 1; i <= 5; i++) {
                stream.offer(i);
            }
            
            System.out.println("投递了5条消息，流大小: " + stream.size());
            
            // 消费但不确认
            try {
                for (int i = 0; i < 3; i++) {
                    MessageStream.StreamMessage<Integer> msg = stream.poll(1, TimeUnit.SECONDS);
                    if (msg != null) {
                        System.out.println("消费消息: " + msg.getMessage() + " (ID: " + msg.getMessageId() + ")");
                        // 故意不ACK，模拟处理失败
                    }
                }
                
                System.out.println("消费后流大小: " + stream.size());
                System.out.println("处理中消息数量: " + stream.processingSize());
                
                // 现在确认部分消息
                // 注意：这里需要知道消息ID，实际应用中应该保存
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } finally {
            // 清理过期消息
            int cleaned = stream.cleanupExpiredMessages();
            System.out.println("清理了 " + cleaned + " 条过期消息");
        }
    }
    
    /**
     * 示例3: CLAIM功能演示
     */
    private static void claimDemo() {
        System.out.println("\n=== 示例3: CLAIM功能演示 ===");
        
        MessageStream<String> stream = new InMemoryMessageStream<>("claim-demo", String.class);
        
        try {
            // 投递消息
            String messageId = stream.offer("需要重新处理的消息");
            System.out.println("投递消息: " + messageId);
            
            // 消费消息
            try {
                MessageStream.StreamMessage<String> msg = stream.poll(1, TimeUnit.SECONDS);
                if (msg != null) {
                    System.out.println("消费消息: " + msg.getMessage());
                    System.out.println("处理中消息数量: " + stream.processingSize());
                    
                    // 模拟处理失败，需要重新认领
                    boolean claimResult = stream.claim(msg.getMessageId(), 10, TimeUnit.SECONDS);
                    System.out.println("认领消息: " + (claimResult ? "成功" : "失败"));
                    
                    if (claimResult) {
                        System.out.println("认领后流大小: " + stream.size());
                        System.out.println("认领后处理中消息数量: " + stream.processingSize());
                        
                        // 重新消费
                        MessageStream.StreamMessage<String> reclaimedMsg = stream.poll(1, TimeUnit.SECONDS);
                        if (reclaimedMsg != null) {
                            System.out.println("重新消费消息: " + reclaimedMsg.getMessage());
                            
                            // 这次成功处理
                            boolean ackResult = stream.ack(reclaimedMsg.getMessageId());
                            System.out.println("确认重新处理的消息: " + (ackResult ? "成功" : "失败"));
                        }
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } finally {
            stream.cleanupExpiredMessages();
        }
    }
    
    /**
     * 示例4: 通过注册表创建
     */
    private static void registryUsage() {
        System.out.println("\n=== 示例4: 通过注册表创建MessageStream ===");
        
        try {
            // 通过注册表创建内存消息流
            MessageStream<String> memoryStream = MessageStreamProviderRegistry.createStream(
                MessageProviderType.IN_MEMORY, "registry-demo", String.class, java.util.Collections.emptyMap()
            );
            
            System.out.println("通过注册表创建的内存流: " + memoryStream.getName());
            
            // 基本操作
            String messageId = memoryStream.offer("注册表创建的消息");
            System.out.println("投递消息: " + messageId);
            
            try {
                MessageStream.StreamMessage<String> msg = memoryStream.poll(1, TimeUnit.SECONDS);
                if (msg != null) {
                    System.out.println("消费消息: " + msg.getMessage());
                    memoryStream.ack(msg.getMessageId());
                    System.out.println("确认消息成功");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("消费消息时被中断: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("创建Redis Stream失败（可能Redis未启动）: " + e.getMessage());
        }
    }
} 