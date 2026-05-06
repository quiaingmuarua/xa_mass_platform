package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned storage boundary for transport delivery handoff.
 *
 * <p>This contract is transport-specific but intentionally Redis-friendly:
 * queue ownership is always the canonical {@code (adapterId, routeKey)} pair,
 * FIFO is per queue key, and callers must not depend on JVM-local queue scans
 * or waiter identity.
 *
 * <p>{@link #poll(String, String, int, long, TimeUnit)} returns transport
 * delivery status, but store implementations should throw
 * {@link InterruptedException} instead of materializing an "interrupted"
 * result. {@link TransportDeliveryService} is responsible for converting
 * thread interruption into a poll result for adapter-facing callers.
 *
 * <p>{@link #shutdown()} is a store-availability boundary, not task lifecycle
 * truth. After shutdown the store must reject new enqueue/poll work and wake
 * blocked pollers. Clearing queued backlog is reference behavior of the
 * current in-memory implementation; future distributed implementations may
 * provide equivalent unavailable semantics without reproducing every local JVM
 * detail internally.
 */
public interface TransportDeliveryStore {

    DispatchOutcome enqueue(TransportDispatchEnvelope envelope);

    List<TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems);

    TransportDeliveryPollResult poll(String adapterId,
                                     String routeKey,
                                     int maxItems,
                                     long timeout,
                                     TimeUnit unit) throws InterruptedException;

    TransportDeliveryStoreStats stats();

    void shutdown();
}
