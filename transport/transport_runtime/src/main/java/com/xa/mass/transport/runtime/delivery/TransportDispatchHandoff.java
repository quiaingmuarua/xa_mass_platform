package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Producer/consumer handoff for already assigned dispatch items.
 */
public interface TransportDispatchHandoff {

    List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch);

    List<DispatchMessage> poll(String adapterMailboxKey,
                               int maxItems,
                               long timeoutMillis) throws InterruptedException;

    void shutdown();
}
