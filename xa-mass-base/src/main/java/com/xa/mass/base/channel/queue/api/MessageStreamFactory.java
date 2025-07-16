package com.xa.mass.base.channel.queue.api;

import com.xa.mass.base.channel.queue.memory.InMemoryMessageStream;
import com.xa.mass.base.channel.queue.redis.LettuceRedisStream;
import java.util.Map;

public class MessageStreamFactory {
    public static <T> MessageStream<T> create(String type, String queueKey, Class<T> messageType, Map<String, String> extraParams) {
        if ("memory".equalsIgnoreCase(type)) {
            return new InMemoryMessageStream<>(queueKey, messageType, extraParams);
        } else if ("redis".equalsIgnoreCase(type)) {
            return new LettuceRedisStream<>(queueKey, messageType, extraParams);
        } else {
            throw new IllegalArgumentException("Unknown stream type: " + type);
        }
    }

    public static <T> MessageStream<T> create(String type, String queueKey, Class<T> messageType) {
        return create(type, queueKey, messageType, null);
    }
} 