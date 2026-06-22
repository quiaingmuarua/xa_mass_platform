package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned storage boundary for transport delivery handoff.
 *
 * <p>This contract is transport-specific but intentionally Redis-friendly.
 * Queue ownership is the transport runtime {@code adapterMailboxKey}; assigned
 * polling delivery is selected by {@code selectedWorkerId} under that shared
 * mailbox owner.
 *
 * <p>{@link #poll(String, String, int, long, TimeUnit)} returns transport
 * delivery status, but store implementations should throw
 * {@link InterruptedException} instead of materializing interruption as a poll
 * status. Adapter-facing callers that need interruption handling should do so
 * above the store boundary.
 *
 * <p>{@link #shutdown()} is a store-availability boundary, not task lifecycle
 * truth. After shutdown the store must reject new enqueue/poll work and wake
 * blocked pollers. Clearing queued backlog is reference behavior of the
 * current in-memory implementation; future distributed implementations may
 * provide equivalent unavailable semantics without reproducing every local JVM
 * detail internally.
 */
public interface TransportDeliveryStore {

    DispatchOutcome enqueue(String adapterMailboxKey, DispatchRoutingItem item);

    List<DispatchRoutingItem> drain(String adapterMailboxKey, String selectedWorkerId, int maxItems);

    TransportDeliveryPollResult poll(String adapterMailboxKey,
                                     String selectedWorkerId,
                                     int maxItems,
                                     long timeout,
                                     TimeUnit unit) throws InterruptedException;

    TransportDeliveryStoreStats stats();

    void shutdown();
}
