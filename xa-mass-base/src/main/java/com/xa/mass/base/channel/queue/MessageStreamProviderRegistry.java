package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.api.MessageStream;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.queue.redis.LettuceRedisStream;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 消息流提供者注册表
 * 用于注册和管理不同类型的消息流提供者
 */
public class MessageStreamProviderRegistry {
    
    private static final Map<String, Function<String, MessageStream<?>>> providers = new ConcurrentHashMap<>();
    
    static {
        // 内存消息流
        register(QueueProviderType.IN_MEMORY_STREAM, name -> new InMemoryMessageStream<>(name));
        
        // Redis消息流（要求先手动初始化RedisConnectionManager，否则抛异常）
        register(QueueProviderType.REDIS_STREAM, name -> {
            // 不再自动init，没初始化直接抛错
            return new LettuceRedisStream<>("stream:" + name, name, Object.class);
        });
    }

    // 只保留枚举相关的 register
    public static void register(QueueProviderType type, Function<String, MessageStream<?>> provider) {
        providers.put(type.toString(), provider);
    }
    
    public static void register(QueueProviderType type, java.util.function.Supplier<MessageStream<?>> supplier) {
        register(type, name -> supplier.get());
    }
    
    // 只保留枚举相关的 createStream
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(QueueProviderType type, String name) {
        Function<String, MessageStream<?>> provider = providers.get(type.toString());
        if (provider == null) {
            throw new IllegalArgumentException("No provider registered for type: " + type);
        }
        return (MessageStream<T>) provider.apply(name);
    }
    
    public static <T> MessageStream<T> createStream(QueueProviderType type) {
        return createStream(type, "default");
    }
    
    /**
     * 检查提供者是否存在
     * 
     * @param type 提供者类型
     * @return 是否存在
     */
    public static boolean hasProvider(QueueProviderType type) {
        return providers.containsKey(type.toString());
    }
    
    /**
     * 获取所有已注册的提供者类型
     * 
     * @return 提供者类型集合
     */
    public static java.util.Set<QueueProviderType> getRegisteredTypes() {
        java.util.Set<QueueProviderType> set = new java.util.HashSet<>();
        for (String key : providers.keySet()) {
            set.add(QueueProviderType.fromString(key));
        }
        return set;
    }
    
    /**
     * 移除提供者
     * 
     * @param type 提供者类型
     */
    public static void removeProvider(QueueProviderType type) {
        providers.remove(type.toString());
    }
    
    /**
     * 清空所有提供者
     */
    public static void clear() {
        providers.clear();
        // 重新注册默认提供者
        register(QueueProviderType.IN_MEMORY_STREAM, name -> new InMemoryMessageStream<>(name));
    }
} 