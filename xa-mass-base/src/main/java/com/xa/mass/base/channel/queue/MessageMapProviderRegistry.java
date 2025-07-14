package com.xa.mass.base.channel.queue;

import com.xa.mass.base.channel.queue.api.MessageMap;
import com.xa.mass.base.channel.queue.memory.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.redis.LettuceRedisMessageMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

/**
 * 消息映射提供者注册表
 * 用于注册和管理不同类型的消息映射提供者
 */
public class MessageMapProviderRegistry {
    // type -> (queueKey, valueType) -> MessageMap
    private static final ConcurrentMap<String, BiFunction<String, Class<?>, MessageMap<String, ?>>> providers = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, MessageMap<String, ?>> mapCache = new ConcurrentHashMap<>();

    static {
        // 注册默认提供者
        register(QueueProviderType.IN_MEMORY, InMemoryMessageMap::new);
        register(QueueProviderType.REDIS, LettuceRedisMessageMap::new);
    }

    /**
     * 注册消息映射提供者
     * @param type 队列类型
     * @param provider 提供者函数 (queueKey, valueType) -> MessageMap
     */
    public static void register(QueueProviderType type, BiFunction<String, Class<?>, MessageMap<String, ?>> provider) {
        providers.put(type.toString(), provider);
    }

    /**
     * 创建MessageMap实例（必须传queueKey和valueType）
     * @param type 队列类型
     * @param queueKey 映射键名
     * @param valueType 值类型Class
     * @param <V> 值类型
     * @return MessageMap实例
     */
    @SuppressWarnings("unchecked")
    public static <V> MessageMap<String, V> createMap(QueueProviderType type, String queueKey, Class<V> valueType) {
        String cacheKey = queueKey + ":" + type + ":" + valueType.getSimpleName();
        return (MessageMap<String, V>) mapCache.computeIfAbsent(cacheKey, k -> {
            BiFunction<String, Class<?>, MessageMap<String, ?>> provider = providers.get(type.toString());
            if (provider == null) {
                throw new IllegalArgumentException("No provider registered for type: " + type);
            }
            return provider.apply(queueKey, valueType);
        });
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