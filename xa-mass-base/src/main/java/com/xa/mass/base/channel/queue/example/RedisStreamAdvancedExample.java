package com.xa.mass.base.channel.queue.example;

import com.xa.mass.base.channel.queue.api.MessageStream;
import com.xa.mass.base.channel.queue.redis.LettuceRedisStream;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisStreamCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream高级功能示例
 * 展示批量ACK和统计功能
 */
public class RedisStreamAdvancedExample {
    
    public static void main(String[] args) {
        // 创建Redis连接
        RedisClient client = RedisClient.create("redis://localhost:6379");
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisStreamCommands<String, String> commands = connection.sync();
        
        try {
            // 示例1: Redis Stream批量ACK功能
            redisBatchAckDemo(commands);
            
            // 示例2: Redis Stream统计功能
            redisStatsDemo(commands);
            
            // 示例3: Redis Stream批量处理完整流程
            redisBatchProcessingDemo(commands);
            
        } finally {
            // 清理资源
            connection.close();
            client.shutdown();
        }
    }
    
    /**
     * 示例1: Redis Stream批量ACK功能演示
     */
    private static void redisBatchAckDemo(RedisStreamCommands<String, String> commands) {
        System.out.println("=== Redis Stream示例1: 批量ACK功能演示 ===");
        
        String streamKey = "redis-batch-ack-demo";
        String group = "demo-group";
        String consumer = "demo-consumer";
        
        MessageStream<String> stream = new LettuceRedisStream<>(streamKey, String.class, java.util.Map.of("group", group, "consumerName", consumer));
        
        try {
            // 投递多个消息
            List<String> messageIds = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                String messageId = stream.offer("Redis-Message-" + i);
                messageIds.add(messageId);
                System.out.println("投递消息: " + messageId + " -> Redis-Message-" + i);
            }
            
            System.out.println("投递完成，Stream大小: " + stream.size());
            
            // 批量消费消息
            List<MessageStream.StreamMessage<String>> messages = stream.pollBatch(4, 3, TimeUnit.SECONDS);
            System.out.println("批量消费到 " + messages.size() + " 条消息");
            
            // 收集消息ID
            List<String> consumedMessageIds = new ArrayList<>();
            for (MessageStream.StreamMessage<String> msg : messages) {
                consumedMessageIds.add(msg.getMessageId());
                System.out.println("消费消息: " + msg.getMessageId() + " -> " + msg.getMessage());
            }
            
            System.out.println("处理中消息数量: " + stream.processingSize());
            
            // 批量确认消息
            int ackCount = stream.ackBatch(consumedMessageIds);
            System.out.println("批量确认成功: " + ackCount + " 条消息");
            
            System.out.println("确认后处理中消息数量: " + stream.processingSize());
            
            // 显示统计信息
            MessageStream.StreamStats stats = stream.getStats();
            System.out.println("最终统计: " + stats);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Redis批量ACK演示被中断: " + e.getMessage());
        }
    }
    
    /**
     * 示例2: Redis Stream统计功能演示
     */
    private static void redisStatsDemo(RedisStreamCommands<String, String> commands) {
        System.out.println("\n=== Redis Stream示例2: 统计功能演示 ===");
        
        String streamKey = "redis-stats-demo";
        String group = "stats-group";
        String consumer = "stats-consumer";
        
        MessageStream<Integer> stream = new LettuceRedisStream<>(streamKey, Integer.class, java.util.Map.of("group", group, "consumerName", consumer));
        
        try {
            // 初始状态
            MessageStream.StreamStats initialStats = stream.getStats();
            System.out.println("初始状态: " + initialStats);
            
            // 投递消息
            for (int i = 1; i <= 6; i++) {
                stream.offer(i);
            }
            
            MessageStream.StreamStats afterOfferStats = stream.getStats();
            System.out.println("投递后状态: " + afterOfferStats);
            
            // 消费部分消息
            List<MessageStream.StreamMessage<Integer>> messages = stream.pollBatch(4, 2, TimeUnit.SECONDS);
            System.out.println("消费了 " + messages.size() + " 条消息");
            
            MessageStream.StreamStats afterConsumeStats = stream.getStats();
            System.out.println("消费后状态: " + afterConsumeStats);
            
            // 确认部分消息
            List<String> messageIds = new ArrayList<>();
            for (int i = 0; i < Math.min(3, messages.size()); i++) {
                messageIds.add(messages.get(i).getMessageId());
            }
            
            int ackCount = stream.ackBatch(messageIds);
            System.out.println("确认了 " + ackCount + " 条消息");
            
            MessageStream.StreamStats afterAckStats = stream.getStats();
            System.out.println("确认后状态: " + afterAckStats);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Redis统计演示被中断: " + e.getMessage());
        }
    }
    
    /**
     * 示例3: Redis Stream批量处理完整流程
     */
    private static void redisBatchProcessingDemo(RedisStreamCommands<String, String> commands) {
        System.out.println("\n=== Redis Stream示例3: 批量处理完整流程 ===");
        
        String streamKey = "redis-batch-processing-demo";
        String group = "processing-group";
        String consumer = "processing-consumer";
        
        MessageStream<String> stream = new LettuceRedisStream<>(streamKey, String.class, java.util.Map.of("group", group, "consumerName", consumer));
        
        try {
            // 投递大量消息
            int messageCount = 15;
            for (int i = 1; i <= messageCount; i++) {
                stream.offer("Redis-Task-" + i);
            }
            
            System.out.println("投递了 " + messageCount + " 条消息");
            System.out.println("初始统计: " + stream.getStats());
            
            // 批量处理循环
            int totalProcessed = 0;
            int totalAcked = 0;
            int batchSize = 4;
            
            while (totalProcessed < messageCount) {
                // 批量消费
                List<MessageStream.StreamMessage<String>> batch = stream.pollBatch(batchSize, 2, TimeUnit.SECONDS);
                
                if (batch.isEmpty()) {
                    System.out.println("没有更多消息，退出处理");
                    break;
                }
                
                System.out.println("处理批次: " + batch.size() + " 条消息");
                
                // 模拟处理逻辑
                List<String> successMessageIds = new ArrayList<>();
                List<String> failedMessageIds = new ArrayList<>();
                
                for (MessageStream.StreamMessage<String> msg : batch) {
                    // 模拟处理：偶数成功，奇数失败
                    boolean success = Integer.parseInt(msg.getMessage().substring(11)) % 2 == 0;
                    
                    if (success) {
                        successMessageIds.add(msg.getMessageId());
                        System.out.println("处理成功: " + msg.getMessage());
                    } else {
                        failedMessageIds.add(msg.getMessageId());
                        System.out.println("处理失败: " + msg.getMessage());
                    }
                }
                
                // 批量确认成功的消息
                if (!successMessageIds.isEmpty()) {
                    int ackCount = stream.ackBatch(successMessageIds);
                    totalAcked += ackCount;
                    System.out.println("批量确认成功: " + ackCount + " 条消息");
                }
                
                // 对于失败的消息，可以选择重新投递或删除
                if (!failedMessageIds.isEmpty()) {
                    // 这里简化处理，直接删除失败的消息
                    stream.ackBatch(failedMessageIds);
                    System.out.println("删除失败消息: " + failedMessageIds.size() + " 条");
                }
                
                totalProcessed += batch.size();
                
                // 显示当前统计
                MessageStream.StreamStats currentStats = stream.getStats();
                System.out.println("当前统计: " + currentStats);
                System.out.println("已处理: " + totalProcessed + ", 已确认: " + totalAcked);
                System.out.println("---");
            }
            
            System.out.println("Redis Stream批量处理完成!");
            System.out.println("最终统计: " + stream.getStats());
            System.out.println("总处理数: " + totalProcessed + ", 总确认数: " + totalAcked);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Redis批量处理演示被中断: " + e.getMessage());
        }
    }
} 