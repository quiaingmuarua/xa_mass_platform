package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.InMemoryKeyedBlockingQueueStore;
import com.xa.mass.runtime.queue.KeyedBlockingQueueStore;
import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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

    InMemoryTransportDeliveryStore(KeyedBlockingQueueStore<DeliveryQueueKey, QueuedPulledDispatch> queueStore,
                                   int maxItemsPerRoute) {
        this.delegate = new QueueBackedTransportDeliveryStore(
                Objects.requireNonNull(queueStore, "queueStore"),
                maxItemsPerRoute
        );
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
}
