package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.api.MessageStream;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.queue.redis.LettuceRedisStream;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 消息流提供者注册表
 * 用于注册和管理不同类型的消息流提供者
 */
public class MessageStreamProviderRegistry {
    
    private static final ConcurrentMap<String, MessageStream<?>> streamCache = new ConcurrentHashMap<>();
    
    /**
     * 创建MessageStream实例
     * @param type 队列类型
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(QueueProviderType type, String name, Class<T> messageType) {
        return createStream(type, name, messageType, null, null);
    }
    
    /**
     * 创建MessageStream实例（带消费者组信息）
     * @param type 队列类型
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param group 消费者组名
     * @param consumer 消费者名
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(QueueProviderType type, String name, Class<T> messageType, String group, String consumer) {
        final QueueProviderType realType;
        if (type == null) {
            throw new IllegalArgumentException("QueueProviderType cannot be null");
        } else if (type instanceof QueueProviderType) {
            realType = (QueueProviderType) type;
        } else {
            realType = QueueProviderType.fromString(type.toString());
        }
        String cacheKey = name + ":" + realType + ":" + (group != null ? group : "default") + ":" + (consumer != null ? consumer : "default");
        
        return (MessageStream<T>) streamCache.computeIfAbsent(cacheKey, k -> {
            switch (realType) {
                case IN_MEMORY:
                case IN_MEMORY_STREAM:
                    return new InMemoryMessageStream<>(name);
                case REDIS:
                case REDIS_STREAM:
                    return new LettuceRedisStream<>(name, name, messageType, group, consumer);
                default:
                    throw new IllegalArgumentException("Unsupported queue type: " + realType);
            }
        });
    }
    
    /**
     * 创建MessageStream实例（使用默认消费者组）
     * @param type 队列类型
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStreamWithDefaultGroup(QueueProviderType type, String name, Class<T> messageType) {
        String defaultGroup = "default-group";
        String defaultConsumer = "default-consumer";
        return createStream(type, name, messageType, defaultGroup, defaultConsumer);
    }
    
    /**
     * 创建MessageStream实例（使用字符串类型）
     * @param typeName 队列类型名称
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(String typeName, String name, Class<T> messageType) {
        QueueProviderType type = QueueProviderType.fromString(typeName);
        return createStream(type, name, messageType);
    }
    
    /**
     * 创建MessageStream实例（使用字符串类型，带消费者组信息）
     * @param typeName 队列类型名称
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param group 消费者组名
     * @param consumer 消费者名
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(String typeName, String name, Class<T> messageType, String group, String consumer) {
        QueueProviderType type = QueueProviderType.fromString(typeName);
        return createStream(type, name, messageType, group, consumer);
    }
    
    /**
     * 创建MessageStream实例（使用字符串类型，使用默认消费者组）
     * @param typeName 队列类型名称
     * @param name 流名称
     * @param messageType 消息类型Class
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStreamWithDefaultGroup(String typeName, String name, Class<T> messageType) {
        QueueProviderType type = QueueProviderType.fromString(typeName);
        return createStreamWithDefaultGroup(type, name, messageType);
    }
    
    /**
     * 清理缓存
     */
    public static void clearCache() {
        streamCache.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public static int getCacheSize() {
        return streamCache.size();
    }
    
    /**
     * 移除指定的流缓存
     */
    public static void removeFromCache(String name) {
        streamCache.entrySet().removeIf(entry -> entry.getKey().startsWith(name + ":"));
    }
} 