package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.runtime.redis.queue.RedisKeyedBlockingQueueStore;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueCodec;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueNamespace;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueOptions;
import com.xa.mass.runtime.redis.queue.RedisKeyedQueueScripts;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.RedisTransportNamespaces;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed transport delivery store that keeps transport addressing and
 * outcome mapping inside transport while delegating queue mechanics to the
 * shared Redis keyed queue primitive.
 */
public final class RedisTransportDeliveryStore implements TransportDeliveryStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS;
    public static final int DEFAULT_MAX_ITEMS_PER_ROUTE = InMemoryTransportDeliveryStore.DEFAULT_MAX_ITEMS_PER_ROUTE;
    public static final String DEFAULT_NAMESPACE_PREFIX = RedisTransportNamespaces.DELIVERY;

    private final QueueBackedTransportDeliveryStore delegate;

    public RedisTransportDeliveryStore(String redisUri) {
        this(redisUri, DEFAULT_NAMESPACE_PREFIX, DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_ROUTE);
    }

    public RedisTransportDeliveryStore(String redisUri,
                                       String namespacePrefix,
                                       int maxQueuedItems,
                                       int maxItemsPerRoute) {
        this(
                RedisClient.create(Objects.requireNonNull(redisUri, "redisUri")),
                namespacePrefix,
                maxQueuedItems,
                maxItemsPerRoute,
                true
        );
    }

    RedisTransportDeliveryStore(RedisClient redisClient,
                                String namespacePrefix,
                                int maxQueuedItems,
                                int maxItemsPerRoute,
                                boolean ownsClient) {
        this(
                new RedisKeyedBlockingQueueStore<>(
                        Objects.requireNonNull(redisClient, "redisClient"),
                        new RedisKeyedQueueNamespace(namespacePrefix),
                        new DeliveryQueueCodec(),
                        RedisKeyedQueueOptions.defaults(maxQueuedItems),
                        new RedisKeyedQueueScripts(),
                        ownsClient
                ),
                maxItemsPerRoute
        );
    }

    RedisTransportDeliveryStore(StatefulRedisConnection<String, String> connection,
                                String namespacePrefix,
                                int maxQueuedItems,
                                int maxItemsPerRoute) {
        this(
                new RedisKeyedBlockingQueueStore<>(
                        connection,
                        new RedisKeyedQueueNamespace(namespacePrefix),
                        new DeliveryQueueCodec(),
                        RedisKeyedQueueOptions.defaults(maxQueuedItems),
                        new RedisKeyedQueueScripts()
                ),
                maxItemsPerRoute
        );
    }

    RedisTransportDeliveryStore(KeyedBlockingQueueStore<DeliveryQueueKey, QueuedPulledDispatch> queueStore,
                                int maxItemsPerRoute) {
        this.delegate = new QueueBackedTransportDeliveryStore(queueStore, maxItemsPerRoute);
    }

    @Override
    public DispatchOutcome enqueue(String adapterId, String deliveryQueueKey, QueuedPulledDispatch item) {
        return delegate.enqueue(adapterId, deliveryQueueKey, item);
    }

    @Override
    public List<QueuedPulledDispatch> drain(String deliveryQueueKey, String selectedWorkerId, int maxItems) {
        return delegate.drain(deliveryQueueKey, selectedWorkerId, maxItems);
    }

    @Override
    public TransportDeliveryPollResult poll(String deliveryQueueKey,
                                            String selectedWorkerId,
                                            int maxItems,
                                            long timeout,
                                            TimeUnit unit) throws InterruptedException {
        return delegate.poll(deliveryQueueKey, selectedWorkerId, maxItems, timeout, unit);
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        return delegate.stats();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    private static final class DeliveryQueueCodec implements RedisKeyedQueueCodec<DeliveryQueueKey, QueuedPulledDispatch> {

        private final RedisQueuedPulledDispatchCodec codec = new RedisQueuedPulledDispatchCodec();

        @Override
        public String encodeKeyPart(DeliveryQueueKey key) {
            return codec.encodeKeyPart(key);
        }

        @Override
        public DeliveryQueueKey decodeKeyPart(String encodedKeyPart) {
            return codec.decodeKeyPart(encodedKeyPart);
        }

        @Override
        public byte[] encodeValue(com.xa.mass.runtime.queue.KeyedQueueEntry<QueuedPulledDispatch> entry) {
            return codec.encodeEntry(entry);
        }

        @Override
        public com.xa.mass.runtime.queue.KeyedQueueEntry<QueuedPulledDispatch> decodeValue(byte[] bytes) {
            return codec.decodeEntry(bytes);
        }
    }
}
