package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.redis.LettuceRedisMessageMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 消息映射提供者注册表
 * 用于注册和管理不同类型的消息映射提供者
 */
public class MessageMapProviderRegistry {
    
    private static final ConcurrentMap<String, MessageMap<?, ?>> mapCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Function<String, MessageMap<?, ?>>> providers = new ConcurrentHashMap<>();
    
    static {
        // 注册默认提供者
        register(QueueProviderType.IN_MEMORY, InMemoryMessageMap::new);
        register(QueueProviderType.REDIS, name -> {
            // Redis MessageMap 需要类型信息，这里提供一个通用的实现
            // 实际使用时应该通过 createMap 方法指定具体的类型
            throw new UnsupportedOperationException("Redis MessageMap requires type information. Use createMap(type, name, keyType, valueType) instead.");
        });
    }
    
    /**
     * 注册消息映射提供者
     * @param type 队列类型
     * @param provider 提供者函数
     */
    public static void register(QueueProviderType type, Function<String, MessageMap<?, ?>> provider) {
        providers.put(type.toString(), provider);
    }
    
    /**
     * 创建MessageMap实例（内存类型）
     * @param type 队列类型
     * @param name 映射名称
     * @param <K> 键类型
     * @param <V> 值类型
     * @return MessageMap实例
     */
    @SuppressWarnings("unchecked")
    public static <K, V> MessageMap<K, V> createMap(QueueProviderType type, String name) {
        if (type == QueueProviderType.REDIS) {
            throw new IllegalArgumentException("Redis MessageMap requires type information. Use createMap(type, name, keyType, valueType) instead.");
        }
        
        String cacheKey = name + ":" + type;
        return (MessageMap<K, V>) mapCache.computeIfAbsent(cacheKey, k -> {
            Function<String, MessageMap<?, ?>> provider = providers.get(type.toString());
            if (provider == null) {
                throw new IllegalArgumentException("No provider registered for type: " + type);
            }
            return provider.apply(name);
        });
    }
    
    /**
     * 创建MessageMap实例（支持Redis等需要类型信息的实现）
     * @param type 队列类型
     * @param name 映射名称
     * @param keyType 键类型Class
     * @param valueType 值类型Class
     * @param <K> 键类型
     * @param <V> 值类型
     * @return MessageMap实例
     */
    @SuppressWarnings("unchecked")
    public static <K, V> MessageMap<K, V> createMap(QueueProviderType type, String name, Class<K> keyType, Class<V> valueType) {
        String cacheKey = name + ":" + type + ":" + keyType.getSimpleName() + ":" + valueType.getSimpleName();
        
        return (MessageMap<K, V>) mapCache.computeIfAbsent(cacheKey, k -> {
            switch (type) {
                case IN_MEMORY:
                    return new InMemoryMessageMap<>(name);
                case REDIS:
                    return new LettuceRedisMessageMap<>(name, keyType, valueType);
                default:
                    Function<String, MessageMap<?, ?>> provider = providers.get(type.toString());
                    if (provider == null) {
                        throw new IllegalArgumentException("No provider registered for type: " + type);
                    }
                    return provider.apply(name);
            }
        });
    }
    
    /**
     * 创建MessageMap实例（使用默认名称）
     * @param type 队列类型
     * @param <K> 键类型
     * @param <V> 值类型
     * @return MessageMap实例
     */
    public static <K, V> MessageMap<K, V> createMap(QueueProviderType type) {
        return createMap(type, "default");
    }
    
    /**
     * 清理缓存
     */
    public static void clearCache() {
        mapCache.clear();
    }
    
    /**
     * 检查提供者是否存在
     * @param type 队列类型
     * @return 是否存在
     */
    public static boolean hasProvider(QueueProviderType type) {
        return providers.containsKey(type.toString());
    }
    
    /**
     * 移除提供者
     * @param type 队列类型
     */
    public static void removeProvider(QueueProviderType type) {
        providers.remove(type.toString());
    }
} 