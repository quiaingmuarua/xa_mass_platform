package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.api.MessageQueue;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageQueue;
import com.xa.mass.base.channel.queue.redis.LettuceRedisQueue;
import com.xa.mass.base.channel.queue.redis.RedisConnectionManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 消息队列提供者注册表
 * 用于注册和管理不同类型的队列提供者，支持函数式编程风格
 */
public class MessageQueueProviderRegistry {
    
    private static final Map<String, Function<String, MessageQueue<?>>> providers = new ConcurrentHashMap<>();
    
    // 预定义的提供者类型
    public static final String IN_MEMORY = "memory";
    public static final String REDIS = "redis";
    public static final String DATABASE = "database";
    public static final String KAFKA = "kafka";
    public static final String RABBITMQ = "rabbitmq";
    
    static {
        // 内存队列
        register(IN_MEMORY, name -> new InMemoryMessageQueue<>(name));

        // Redis队列（要求先手动初始化RedisConnectionManager，否则抛异常）
        register(REDIS, name -> {
            // 不再自动init，没初始化直接抛错
            return new LettuceRedisQueue<>("queue:" + name, name, Object.class);
        });
    }

    /**
     * 注册队列提供者
     * 
     * @param type 提供者类型
     * @param provider 提供者函数，接收队列名称，返回队列实例
     */
    public static void register(String type, Function<String, MessageQueue<?>> provider) {
        providers.put(type, provider);
    }
    
    /**
     * 注册队列提供者（简化版本，不关心队列名称）
     * 
     * @param type 提供者类型
     * @param supplier 队列创建函数
     */
    public static void register(String type, java.util.function.Supplier<MessageQueue<?>> supplier) {
        register(type, name -> supplier.get());
    }
    
    /**
     * 创建队列
     * 
     * @param type 提供者类型
     * @param name 队列名称
     * @param <T> 消息类型
     * @return 消息队列实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageQueue<T> createQueue(String type, String name) {
        Function<String, MessageQueue<?>> provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("No provider registered for type: " + type);
        }
        return (MessageQueue<T>) provider.apply(name);
    }
    
    /**
     * 创建队列（使用默认名称）
     * 
     * @param type 提供者类型
     * @param <T> 消息类型
     * @return 消息队列实例
     */
    public static <T> MessageQueue<T> createQueue(String type) {
        return createQueue(type, "default");
    }
    
    /**
     * 检查提供者是否存在
     * 
     * @param type 提供者类型
     * @return 是否存在
     */
    public static boolean hasProvider(String type) {
        return providers.containsKey(type);
    }
    
    /**
     * 获取所有已注册的提供者类型
     * 
     * @return 提供者类型集合
     */
    public static java.util.Set<String> getRegisteredTypes() {
        return providers.keySet();
    }
    
    /**
     * 移除提供者
     * 
     * @param type 提供者类型
     */
    public static void removeProvider(String type) {
        providers.remove(type);
    }
    
    /**
     * 清空所有提供者
     */
    public static void clear() {
        providers.clear();
        // 重新注册默认提供者
        register(IN_MEMORY, name -> new InMemoryMessageQueue<>());
    }
} 