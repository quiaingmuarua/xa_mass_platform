package com.xa.mass.base.channel.messaging;

import com.xa.mass.base.channel.messaging.api.MessageStream;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.messaging.redis.LettuceRedisStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息流提供者注册表
 * 用于注册和管理不同类型的消息流提供者
 */
public class MessageStreamProviderRegistry {
    // type -> (queueKey, messageType) -> MessageStream
    private static final Map<String, TriFunction<String, Class<?>, Map<String, String>, MessageStream<?>>> providers = new ConcurrentHashMap<>();
    private static final Map<String, MessageStream<?>> streamCache = new ConcurrentHashMap<>();

    static {
        // 内存流
        register(MessageProviderType.IN_MEMORY, InMemoryMessageStream::new);
        // Redis流
        register(MessageProviderType.REDIS, LettuceRedisStream::new);
    }

    /**
     * 注册流提供者
     * @param type 队列类型
     * @param provider 提供者函数 (queueKey, messageType, extraParams) -> MessageStream
     */
    public static void register(MessageProviderType type, TriFunction<String, Class<?>, Map<String, String>, MessageStream<?>> provider) {
        providers.put(type.toString(), provider);
    }

    /**
     * 创建流实例（必须传queueKey和messageType，可选extraParams）
     * @param type 队列类型
     * @param queueKey 流键名
     * @param messageType 消息类型Class
     * @param extraParams 扩展参数（可选，包含group、consumerName等）
     * @param <T> 消息类型
     * @return MessageStream实例
     */
    @SuppressWarnings("unchecked")
    public static <T> MessageStream<T> createStream(MessageProviderType type, String queueKey, Class<T> messageType, Map<String, String> extraParams) {
        String cacheKey = queueKey + ":" + type + ":" + messageType.getSimpleName();
        if (extraParams != null) {
            if (extraParams.containsKey("group")) cacheKey += ":" + extraParams.get("group");
            if (extraParams.containsKey("consumerName")) cacheKey += ":" + extraParams.get("consumerName");
        }
        return (MessageStream<T>) streamCache.computeIfAbsent(cacheKey, k -> {
            TriFunction<String, Class<?>, Map<String, String>, MessageStream<?>> provider = providers.get(type.toString());
            if (provider == null) {
                throw new IllegalArgumentException("No provider registered for type: " + type);
            }
            return provider.apply(queueKey, messageType, extraParams);
        });
    }

    /**
     * 清理缓存
     */
    public static void clearCache() {
        streamCache.clear();
    }

    // TriFunction接口定义
    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
} 