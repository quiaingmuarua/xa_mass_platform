package com.xa.mass.runtime.queue;

import java.util.List;
import java.util.concurrent.TimeUnit;

public interface KeyedBlockingQueueStore<K, V> {

    KeyedQueueOfferResult offer(K key, KeyedQueueEntry<V> entry, int maxItemsPerKey);

    List<KeyedQueueEntry<V>> drain(K key, int maxItems);

    KeyedQueuePollResult<V> poll(K key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException;

    KeyedQueueSnapshot<K> snapshot();

    void shutdown();
}
