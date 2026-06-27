package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned dispatch items.
 */
public interface TransportDispatchHandoff extends TransportDispatchQueue {

    default List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
        if (batch == null) {
            throw new NullPointerException("batch");
        }
        return offer(batch.adapterMailboxKey(), batch.items());
    }

    @Override
    List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items);

    @Override
    List<DispatchMessage> poll(String adapterMailboxKey,
                               int maxItems,
                               long timeoutMillis) throws InterruptedException;

    @Override
    void shutdown();
}
