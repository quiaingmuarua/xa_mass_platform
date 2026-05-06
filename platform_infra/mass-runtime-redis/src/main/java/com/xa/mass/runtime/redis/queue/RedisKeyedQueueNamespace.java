package com.xa.mass.runtime.redis.queue;

import java.util.Objects;

/**
 * Stable Redis key namespace for keyed queue primitives.
 */
public final class RedisKeyedQueueNamespace {

    private final String prefix;

    public RedisKeyedQueueNamespace(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        this.prefix = prefix.trim();
    }

    public String prefix() {
        return prefix;
    }

    public String queueKey(String encodedKeyPart) {
        return prefix + ":q:" + requireKeyPart(encodedKeyPart);
    }

    public String metaKey(String encodedKeyPart) {
        return prefix + ":meta:" + requireKeyPart(encodedKeyPart);
    }

    public String activeQueuesKey() {
        return prefix + ":queues";
    }

    public String globalStatsKey() {
        return prefix + ":stats";
    }

    private static String requireKeyPart(String encodedKeyPart) {
        if (encodedKeyPart == null || encodedKeyPart.isBlank()) {
            throw new IllegalArgumentException("encodedKeyPart must not be blank");
        }
        return encodedKeyPart.trim();
    }
}
