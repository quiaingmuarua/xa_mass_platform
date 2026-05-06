package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.InMemoryKeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * In-memory runtime delivery store used by embedded transport runtimes.
 */
public final class InMemoryTransportDeliveryStore implements TransportDeliveryStore {

    public static final int DEFAULT_MAX_QUEUED_ITEMS = 1_000_000;
    public static final int DEFAULT_MAX_ITEMS_PER_ROUTE = 10_000;

    private final QueueBackedTransportDeliveryStore delegate;

    public InMemoryTransportDeliveryStore() {
        this(DEFAULT_MAX_QUEUED_ITEMS, DEFAULT_MAX_ITEMS_PER_ROUTE);
    }

    public InMemoryTransportDeliveryStore(int maxQueuedItems) {
        this(maxQueuedItems, DEFAULT_MAX_ITEMS_PER_ROUTE, System::currentTimeMillis);
    }

    public InMemoryTransportDeliveryStore(int maxQueuedItems, int maxItemsPerRoute) {
        this(maxQueuedItems, maxItemsPerRoute, System::currentTimeMillis);
    }

    InMemoryTransportDeliveryStore(int maxQueuedItems, int maxItemsPerRoute, LongSupplier currentTimeMillis) {
        this(new InMemoryKeyedBlockingQueueStore<>(maxQueuedItems, currentTimeMillis), maxItemsPerRoute);
    }

    InMemoryTransportDeliveryStore(KeyedBlockingQueueStore<DeliveryQueueKey, TransportDispatchEnvelope> queueStore,
                                   int maxItemsPerRoute) {
        this.delegate = new QueueBackedTransportDeliveryStore(
                Objects.requireNonNull(queueStore, "queueStore"),
                maxItemsPerRoute
        );
    }

    @Override
    public com.xa.mass.transport.model.DispatchOutcome enqueue(com.xa.mass.transport.model.TransportDispatchEnvelope envelope) {
        return delegate.enqueue(envelope);
    }

    @Override
    public List<com.xa.mass.transport.model.TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems) {
        return delegate.drain(adapterId, routeKey, maxItems);
    }

    @Override
    public TransportDeliveryPollResult poll(String adapterId,
                                            String routeKey,
                                            int maxItems,
                                            long timeout,
                                            java.util.concurrent.TimeUnit unit) throws InterruptedException {
        return delegate.poll(adapterId, routeKey, maxItems, timeout, unit);
    }

    @Override
    public TransportDeliveryStoreStats stats() {
        return delegate.stats();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }
}
