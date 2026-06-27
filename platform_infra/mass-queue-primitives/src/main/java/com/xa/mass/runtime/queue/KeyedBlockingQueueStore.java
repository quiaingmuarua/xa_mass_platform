package com.xa.mass.runtime.queue;

import java.util.List;
import java.util.concurrent.TimeUnit;

public interface KeyedBlockingQueueStore {

    KeyedQueueOfferResult offer(String key, KeyedQueueEntry entry, int maxItemsPerKey);

    List<KeyedQueueEntry> drain(String key, int maxItems);

    KeyedQueuePollResult poll(String key, int maxItems, long timeout, TimeUnit unit) throws InterruptedException;

    int size(String key);

    void shutdown();
}
