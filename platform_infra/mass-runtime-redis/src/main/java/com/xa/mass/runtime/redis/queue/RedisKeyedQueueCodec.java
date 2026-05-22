package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedQueueEntry;

/**
 * Redis-facing codec for keyed queue primitives.
 *
 * <p>This codec owns only stable key-part and value serialization. It must not
 * embed transport-, engine-, or module-specific queue semantics.
 */
public interface RedisKeyedQueueCodec<K, V> {

    String encodeKeyPart(K key);

    K decodeKeyPart(String encodedKeyPart);

    byte[] encodeValue(KeyedQueueEntry<V> entry);

    KeyedQueueEntry<V> decodeValue(byte[] bytes);
}
